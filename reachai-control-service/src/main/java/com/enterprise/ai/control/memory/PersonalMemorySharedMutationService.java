package com.enterprise.ai.control.memory;

import com.enterprise.ai.control.memory.PersonalMemoryIdentityResolver.PersonalMemoryPrincipal;
import com.enterprise.ai.control.memory.PersonalMemoryService.ForgetView;
import com.enterprise.ai.control.memory.PersonalMemoryService.MemoryView;
import com.enterprise.ai.control.memory.PersonalMemoryService.UpdateCommand;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;

/** Deletes only own or currently linked personal memories; link validation and delete share a transaction. */
@Service
public class PersonalMemorySharedMutationService {
    private final PersonalMemoryService memories;
    private final PersonalMemoryIdentityLinkLookup links;
    private final JdbcTemplate jdbc;

    public PersonalMemorySharedMutationService(PersonalMemoryService memories,
            PersonalMemoryIdentityLinkLookup links, JdbcTemplate jdbc) {
        this.memories = memories;
        this.links = links;
        this.jdbc = jdbc;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ForgetView forget(PersonalMemoryPrincipal owner, long memoryId, Set<String> allowedProjects) {
        if (owner == null || memoryId <= 0) throw notFound();
        if (memories.isOwnedBy(owner, memoryId)) return memories.forgetInProjects(owner, memoryId, allowedProjects);
        if (PersonalMemoryPrincipal.LEGACY_DOMAIN.equals(owner.ownerDomain())) throw notFound();
        for (var peer : links.linkedOwners(owner)) {
            if (!memories.isOwnedBy(peer.owner(), memoryId)) continue;
            lockAndValidate(owner, peer);
            // Empty project set permits the peer's personal layer only, checked while locking the memory row.
            return memories.forgetInProjects(peer.owner(), memoryId, Set.of());
        }
        throw notFound();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MemoryView updateLinked(PersonalMemoryPrincipal owner, long linkId, long memoryId, UpdateCommand command) {
        var peer = requirePeer(owner, linkId);
        lockAndValidate(owner, peer);
        return memories.updatePersonalOnly(peer.owner(), memoryId, command);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ForgetView forgetLinked(PersonalMemoryPrincipal owner, long linkId, long memoryId) {
        var peer = requirePeer(owner, linkId);
        lockAndValidate(owner, peer);
        return memories.forgetInProjects(peer.owner(), memoryId, Set.of());
    }

    private PersonalMemoryIdentityLinkLookup.LinkedOwner requirePeer(PersonalMemoryPrincipal owner, long linkId) {
        if (owner == null || linkId <= 0 || PersonalMemoryPrincipal.LEGACY_DOMAIN.equals(owner.ownerDomain())) throw notFound();
        return links.linkedOwners(owner).stream().filter(link -> link.linkId() == linkId).findFirst().orElseThrow(
                PersonalMemorySharedMutationService::notFound);
    }

    private void lockAndValidate(PersonalMemoryPrincipal owner, PersonalMemoryIdentityLinkLookup.LinkedOwner peer) {
        List<Pair> rows = jdbc.query("""
                SELECT left_binding_id, right_binding_id, state, active_marker
                FROM control_personal_memory_identity_link WHERE id=? FOR UPDATE
                """, (rs, row) -> new Pair(rs.getLong(1), rs.getLong(2), rs.getString(3),
                rs.getObject(4) == null ? null : rs.getInt(4)), peer.linkId());
        if (rows.size() != 1 || !"ACTIVE".equals(rows.get(0).state()) || !Integer.valueOf(1).equals(rows.get(0).active())) throw notFound();
        Pair pair = rows.get(0);
        lockAccount(Math.min(pair.left(), pair.right()));
        lockAccount(Math.max(pair.left(), pair.right()));
        if (!links.linkedOwnerForUpdate(owner, peer.linkId()).contains(peer)) throw notFound();
    }

    private void lockAccount(long bindingId) {
        List<Long> locked = jdbc.query("""
                SELECT a.id FROM control_external_user_binding a
                JOIN control_business_user u ON u.id=a.business_user_id
                WHERE a.id=? FOR UPDATE
                """, (rs, row) -> rs.getLong(1), bindingId);
        if (locked.size() != 1) throw notFound();
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "personal memory not found");
    }

    private record Pair(long left, long right, String state, Integer active) { }
}
