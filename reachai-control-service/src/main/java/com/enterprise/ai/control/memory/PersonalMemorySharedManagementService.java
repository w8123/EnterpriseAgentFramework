package com.enterprise.ai.control.memory;

import com.enterprise.ai.control.memory.PersonalMemoryIdentityLinkLookup.LinkedOwner;
import com.enterprise.ai.control.memory.PersonalMemoryIdentityResolver.PersonalMemoryPrincipal;
import com.enterprise.ai.control.memory.PersonalMemoryService.MemoryPage;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/** Bounded, per-link management reads; caller identity is always derived from a verified session. */
@Service
public class PersonalMemorySharedManagementService {
    private final PersonalMemoryIdentityLinkLookup links;
    private final PersonalMemoryService memories;

    public PersonalMemorySharedManagementService(PersonalMemoryIdentityLinkLookup links, PersonalMemoryService memories) {
        this.links = links;
        this.memories = memories;
    }

    public List<SharedSystem> systems(PersonalMemoryPrincipal owner) {
        return links.linkedOwners(owner).stream().map(peer -> new SharedSystem(peer.linkId(),
                peer.owner().ownerDomain().substring("BUSINESS:".length()), accountHint(peer.owner().runtimeUserId()))).toList();
    }

    public MemoryPage list(PersonalMemoryPrincipal owner, long linkId, String keyword, Integer limit, Integer offset) {
        LinkedOwner peer = requirePeer(owner, linkId);
        MemoryPage result = memories.listPersonalForSharing(peer.owner(), keyword, limit, offset);
        if (!links.linkedOwners(owner).contains(peer)) throw notFound();
        return result;
    }

    private LinkedOwner requirePeer(PersonalMemoryPrincipal owner, long linkId) {
        if (owner == null || linkId <= 0) throw notFound();
        return links.linkedOwners(owner).stream().filter(peer -> peer.linkId() == linkId)
                .findFirst().orElseThrow(PersonalMemorySharedManagementService::notFound);
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "shared memory source not found");
    }

    private static String accountHint(String account) {
        if (account == null || account.isBlank()) return "••";
        int count = account.codePointCount(0, account.length());
        if (count <= 2) return "••";
        int keep = Math.min(3, count - 2);
        return "•••" + account.substring(account.offsetByCodePoints(0, count - keep));
    }

    public record SharedSystem(long linkId, String projectCode, String accountHint) { }
}
