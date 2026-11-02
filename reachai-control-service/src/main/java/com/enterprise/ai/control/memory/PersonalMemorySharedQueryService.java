package com.enterprise.ai.control.memory;

import com.enterprise.ai.control.memory.PersonalMemoryIdentityLinkLookup.LinkedOwner;
import com.enterprise.ai.control.memory.PersonalMemoryIdentityResolver.PersonalMemoryPrincipal;
import com.enterprise.ai.control.memory.PersonalMemoryService.QueryCommand;
import com.enterprise.ai.control.memory.PersonalMemoryService.QueryHit;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Internal query for a verified owner; not an authorization boundary for request parameters. */
@Service
public class PersonalMemorySharedQueryService {
    private static final int MAX_LINKS = 32;
    private final PersonalMemoryService memories;
    private final PersonalMemoryIdentityLinkLookup links;

    public PersonalMemorySharedQueryService(PersonalMemoryService memories, PersonalMemoryIdentityLinkLookup links) {
        this.memories = memories;
        this.links = links;
    }

    public SharedResult query(PersonalMemoryPrincipal owner, QueryCommand command) {
        if (owner == null || command == null) throw new IllegalArgumentException("verified owner and query are required");
        int topK = Math.max(1, Math.min(command.topK() == null ? 8 : command.topK(), 50));
        int maxChars = Math.max(256, Math.min(command.maxChars() == null ? 6_000 : command.maxChars(), 32_000));
        var own = memories.query(owner, command);
        List<Candidate> candidates = new ArrayList<>();
        own.hits().forEach(hit -> candidates.add(new Candidate(null, owner, hit)));
        boolean truncated = own.truncated();
        boolean sharingUnavailable = false;
        List<LinkedOwner> linked = List.of();
        if (!PersonalMemoryPrincipal.LEGACY_DOMAIN.equals(owner.ownerDomain())) {
            try { linked = links.linkedOwners(owner); }
            catch (RuntimeException unavailable) { sharingUnavailable = true; }
        }
        if (linked.size() > MAX_LINKS) truncated = true;
        for (LinkedOwner peer : linked.stream().limit(MAX_LINKS).toList()) {
            try {
                var result = memories.query(peer.owner(), new QueryCommand(command.query(), command.types(), topK, maxChars,
                        command.sessionId(), command.traceId(), command.agentId(), null));
                truncated |= result.truncated();
                result.hits().stream().filter(hit -> hit.projectCode() == null)
                        .forEach(hit -> candidates.add(new Candidate(peer, peer.owner(), hit)));
            } catch (RuntimeException unavailable) { sharingUnavailable = true; }
        }
        // Do not use data from a link removed or rebound while the queries were running.
        Set<LinkedOwner> stillLinked = Set.of();
        if (!linked.isEmpty()) {
            try { stillLinked = Set.copyOf(links.linkedOwners(owner)); }
            catch (RuntimeException unavailable) { sharingUnavailable = true; }
        }
        Set<LinkedOwner> validatedLinks = stillLinked;
        candidates.removeIf(c -> c.link() != null && !validatedLinks.contains(c.link()));
        candidates.sort(Comparator.comparingDouble((Candidate c) -> c.hit().score()).reversed()
                .thenComparing(c -> c.link() != null)
                .thenComparing(c -> c.hit().id()));
        Map<Long, SharedHit> selected = new LinkedHashMap<>();
        int used = 0;
        for (Candidate candidate : candidates) {
            QueryHit hit = candidate.hit();
            if (selected.containsKey(hit.id())) continue;
            if (selected.size() >= topK) { truncated = true; break; }
            String content = hit.content();
            if (used + content.length() > maxChars) {
                if (!selected.isEmpty()) { truncated = true; continue; }
                content = content.substring(0, maxChars);
                hit = new QueryHit(hit.id(), hit.type(), hit.title(), content, hit.score(), hit.updatedAt(), hit.projectCode());
                truncated = true;
            }
            selected.put(hit.id(), new SharedHit(candidate.owner(),
                    candidate.link() == null ? null : candidate.link().linkId(), hit));
            used += content.length();
        }
        return new SharedResult(List.copyOf(selected.values()), used, truncated, sharingUnavailable);
    }

    private record Candidate(LinkedOwner link, PersonalMemoryPrincipal owner, QueryHit hit) { }
    public record SharedHit(PersonalMemoryPrincipal owner, Long linkId, QueryHit hit) { }
    public record SharedResult(List<SharedHit> hits, int usedChars, boolean truncated, boolean sharingUnavailable) { }
}
