package com.enterprise.ai.control.memory;

import com.enterprise.ai.control.memory.PersonalMemoryIdentityResolver.PersonalMemoryPrincipal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Resolves only explicitly confirmed, currently valid links; it never migrates memory. */
@Service
public class PersonalMemoryIdentityLinkLookup {
    private final JdbcTemplate jdbc;

    public PersonalMemoryIdentityLinkLookup(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<LinkedOwner> linkedOwners(PersonalMemoryPrincipal owner) {
        return linkedOwners(owner, null);
    }

    /** Use within a transaction after locking the target link; obtains a current, locked read. */
    public List<LinkedOwner> linkedOwnerForUpdate(PersonalMemoryPrincipal owner, long linkId) {
        return linkedOwners(owner, linkId);
    }

    private List<LinkedOwner> linkedOwners(PersonalMemoryPrincipal owner, Long lockedLinkId) {
        if (owner == null || owner.ownerDomain() == null || !owner.ownerDomain().startsWith("BUSINESS:")) {
            return List.of();
        }
        String app = owner.ownerDomain().substring("BUSINESS:".length());
        List<LinkedOwner> peers = new ArrayList<>();
        String query = """
                SELECT l.id, l.tenant_id AS link_tenant, l.state AS link_state,
                       l.left_business_user_id AS l_owner_snapshot, l.left_app_id AS l_app_snapshot,
                       l.left_external_user_id AS l_user_snapshot,
                       l.right_business_user_id AS r_owner_snapshot, l.right_app_id AS r_app_snapshot,
                       l.right_external_user_id AS r_user_snapshot,
                       a.id AS l_id, a.tenant_id AS l_tenant, a.business_user_id AS l_owner,
                       a.app_id AS l_app, a.external_user_id AS l_user, a.status AS l_status,
                       b.id AS r_id, b.tenant_id AS r_tenant, b.business_user_id AS r_owner,
                       b.app_id AS r_app, b.external_user_id AS r_user, b.status AS r_status,
                       ua.tenant_id AS l_owner_tenant, ua.status AS l_owner_status,
                       ub.tenant_id AS r_owner_tenant, ub.status AS r_owner_status
                FROM control_personal_memory_identity_link l
                JOIN control_external_user_binding a ON a.id = l.left_binding_id
                JOIN control_external_user_binding b ON b.id = l.right_binding_id
                JOIN control_business_user ua ON ua.id = a.business_user_id
                JOIN control_business_user ub ON ub.id = b.business_user_id
                WHERE l.tenant_id = ? AND l.state = 'ACTIVE' AND l.active_marker = 1
                  AND ((a.app_id = ? AND a.external_user_id = ?) OR (b.app_id = ? AND b.external_user_id = ?))
                """ + (lockedLinkId == null ? " ORDER BY l.id" : " AND l.id = ? FOR UPDATE");
        Object[] parameters = lockedLinkId == null
                ? new Object[]{owner.tenantId(), app, owner.runtimeUserId(), app, owner.runtimeUserId()}
                : new Object[]{owner.tenantId(), app, owner.runtimeUserId(), app, owner.runtimeUserId(), lockedLinkId};
        jdbc.query(query, rs -> {
            Account left = account(rs, "l");
            Account right = account(rs, "r");
            if (!"ACTIVE".equals(rs.getString("link_state")) || !owner.tenantId().equals(rs.getString("link_tenant"))
                    || !left.validFor(owner.tenantId()) || !right.validFor(owner.tenantId())
                    || left.app().equalsIgnoreCase(right.app())) return;
            Account peer = left.matches(owner) ? right : right.matches(owner) ? left : null;
            if (peer != null) {
                peers.add(new LinkedOwner(rs.getLong("id"), peer.id(),
                        PersonalMemoryPrincipal.business(owner.tenantId(), peer.user(), peer.app())));
            }
        }, parameters);
        return List.copyOf(peers);
    }

    private static Account account(ResultSet rs, String side) throws SQLException {
        return new Account(rs.getLong(side + "_id"), rs.getString(side + "_tenant"),
                rs.getLong(side + "_owner"), rs.getString(side + "_app"), rs.getString(side + "_user"),
                rs.getString(side + "_status"), rs.getString(side + "_owner_tenant"),
                rs.getString(side + "_owner_status"), rs.getLong(side + "_owner_snapshot"),
                rs.getString(side + "_app_snapshot"), rs.getString(side + "_user_snapshot"));
    }

    private record Account(long id, String tenant, long ownerId, String app, String user, String status,
                           String ownerTenant, String ownerStatus, long ownerSnapshot,
                           String appSnapshot, String userSnapshot) {
        boolean validFor(String expectedTenant) {
            return expectedTenant.equals(tenant) && expectedTenant.equals(ownerTenant)
                    && "ACTIVE".equalsIgnoreCase(status) && "ACTIVE".equalsIgnoreCase(ownerStatus)
                    && ownerId == ownerSnapshot && app != null && app.equals(appSnapshot)
                    && user != null && user.equals(userSnapshot);
        }

        boolean matches(PersonalMemoryPrincipal principal) {
            return principal.ownerDomain().equals("BUSINESS:" + app.toLowerCase(Locale.ROOT))
                    && principal.runtimeUserId().equals(user);
        }
    }

    public record LinkedOwner(long linkId, long bindingId, PersonalMemoryPrincipal owner) { }
}
