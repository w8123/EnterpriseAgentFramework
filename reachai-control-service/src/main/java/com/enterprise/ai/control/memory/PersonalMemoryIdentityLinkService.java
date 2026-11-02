package com.enterprise.ai.control.memory;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformPermissions;
import com.enterprise.ai.control.mcp.application.employee.AiToolConnectionService.EmployeeOwner;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;

/** Internal state transitions; public entry points must supply a server-authenticated actor. */
@Service
public class PersonalMemoryIdentityLinkService {
    private static final String ADMIN = "ADMIN";
    private final JdbcTemplate jdbc;

    public PersonalMemoryIdentityLinkService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Administrative inspection includes invalidated snapshots so they can still be revoked. */
    public List<AdminLink> linksForAdministrator(PlatformAuthenticatedSession actor, long bindingId, int offset) {
        requireAdministrator(actor);
        if (bindingId <= 0 || offset < 0) throw invalid("invalid binding or offset");
        return jdbc.query("""
                SELECT id, left_binding_id, right_binding_id, left_app_id, right_app_id,
                       left_external_user_id, right_external_user_id, verification_method, created_at
                FROM control_personal_memory_identity_link
                WHERE (left_binding_id=? OR right_binding_id=?) AND state='ACTIVE' AND active_marker=1
                ORDER BY id DESC LIMIT 51 OFFSET ?
                """, (rs, row) -> {
            boolean left = rs.getLong("left_binding_id") == bindingId;
            String peer = left ? "right" : "left";
            return new AdminLink(rs.getLong("id"), rs.getLong(peer + "_binding_id"),
                    rs.getString(peer + "_app_id"), rs.getString(peer + "_external_user_id"),
                    rs.getString("verification_method"), rs.getObject("created_at", LocalDateTime.class));
        }, bindingId, bindingId, offset);
    }

    public record AdminLink(long id, long peerBindingId, String peerAppId, String peerExternalUserId,
                            String verificationMethod, LocalDateTime createdAt) { }

    @Transactional
    public long linkByAdministrator(PlatformAuthenticatedSession actor, long firstId, long secondId, String evidence) {
        String actorId = requireAdministrator(actor);
        String note = requireEvidence(evidence);
        if (firstId <= 0 || secondId <= 0 || firstId == secondId) throw invalid("two different accounts are required");
        Account left = lockedAccount(Math.min(firstId, secondId));
        Account right = lockedAccount(Math.max(firstId, secondId));
        if (!left.tenant().equals(right.tenant()) || left.app().equalsIgnoreCase(right.app())) {
            throw invalid("accounts must be in the same tenant and different business systems");
        }
        return create(left, right, ADMIN, actorId, note);
    }

    /** Both owners must have been obtained from live, independently authenticated portal sessions. */
    long linkByVerifiedEmployees(EmployeeOwner first, EmployeeOwner second) {
        long firstId = employeeBindingId(first);
        long secondId = employeeBindingId(second);
        if (firstId == secondId) throw invalid("two different accounts are required");
        Account left = lockedAccount(Math.min(firstId, secondId));
        Account right = lockedAccount(Math.max(firstId, secondId));
        Account firstAccount = firstId == left.id() ? left : right;
        Account secondAccount = secondId == left.id() ? left : right;
        if (!matches(firstAccount, first) || !matches(secondAccount, second)
                || !left.tenant().equals(right.tenant()) || left.app().equalsIgnoreCase(right.app())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "business identity changed or systems are incompatible");
        }
        return create(left, right, "SELF", "employee:" + firstId + ":" + secondId, null);
    }

    private long create(Account left, Account right, String method, String actorId, String note) {
        LocalDateTime now = LocalDateTime.now();
        KeyHolder keys = new GeneratedKeyHolder();
        try {
            jdbc.update(connection -> {
                var statement = connection.prepareStatement("""
                        INSERT INTO control_personal_memory_identity_link
                        (tenant_id, left_binding_id, right_binding_id, left_business_user_id, right_business_user_id,
                         left_app_id, left_external_user_id, right_app_id, right_external_user_id,
                         state, active_marker, verification_method, verified_by, verification_note, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', 1, ?, ?, ?, ?, ?)
                        """, Statement.RETURN_GENERATED_KEYS);
                statement.setString(1, left.tenant()); statement.setLong(2, left.id()); statement.setLong(3, right.id());
                statement.setLong(4, left.ownerId()); statement.setLong(5, right.ownerId());
                statement.setString(6, left.app()); statement.setString(7, left.user());
                statement.setString(8, right.app()); statement.setString(9, right.user());
                statement.setString(10, method); statement.setString(11, actorId); statement.setString(12, note);
                statement.setObject(13, now); statement.setObject(14, now);
                return statement;
            }, keys);
        } catch (DuplicateKeyException duplicate) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "identity link already exists");
        }
        Number key = keys.getKey();
        if (key == null) throw new IllegalStateException("identity link id was not returned");
        long linkId = key.longValue();
        event(linkId, "LINK", method, actorId, note, now);
        return linkId;
    }

    private long employeeBindingId(EmployeeOwner employee) {
        if (employee == null || employee.businessUserId() == null || !StringUtils.hasText(employee.tenantId())
                || !StringUtils.hasText(employee.projectCode()) || !StringUtils.hasText(employee.externalUserId())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "verified employee identity is required");
        }
        List<Account> candidates = jdbc.query("""
                SELECT a.id, a.tenant_id, a.business_user_id, a.app_id, a.external_user_id, a.status,
                       u.tenant_id AS owner_tenant, u.status AS owner_status
                FROM control_external_user_binding a JOIN control_business_user u ON u.id=a.business_user_id
                WHERE a.tenant_id=? AND a.business_user_id=? AND a.app_id=? AND a.external_user_id=?
                """, (rs, row) -> new Account(rs.getLong("id"), rs.getString("tenant_id"),
                rs.getLong("business_user_id"), rs.getString("app_id"), rs.getString("external_user_id"),
                rs.getString("status"), rs.getString("owner_tenant"), rs.getString("owner_status")),
                employee.tenantId(), employee.businessUserId(), employee.projectCode(), employee.externalUserId());
        List<Account> exact = candidates.stream().filter(account -> matches(account, employee)).toList();
        if (exact.size() != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "verified employee binding is unavailable");
        return exact.get(0).id();
    }

    void requireVerifiedEmployee(EmployeeOwner employee) {
        employeeBindingId(employee);
    }

    private static boolean matches(Account account, EmployeeOwner employee) {
        return employee != null && employee.businessUserId() != null && account.ownerId() == employee.businessUserId()
                && StringUtils.hasText(account.tenant()) && account.tenant().equals(employee.tenantId())
                && account.tenant().equals(account.ownerTenant()) && StringUtils.hasText(account.app())
                && account.app().equals(employee.projectCode()) && account.app().equals(employee.appId())
                && StringUtils.hasText(account.user()) && account.user().equals(employee.externalUserId())
                && "ACTIVE".equalsIgnoreCase(account.status())
                && "ACTIVE".equalsIgnoreCase(account.ownerStatus());
    }

    @Transactional
    public void revokeByAdministrator(PlatformAuthenticatedSession actor, long linkId, String evidence) {
        String actorId = requireAdministrator(actor);
        String note = requireEvidence(evidence);
        revoke(linkId, ADMIN, actorId, note, null);
    }

    /** Either linked employee may revoke sharing without the other party's cooperation. */
    @Transactional
    public void revokeByEmployee(EmployeeOwner employee, long linkId) {
        revoke(linkId, "SELF", null, null, employee);
    }

    private void revoke(long linkId, String method, String actorId, String note, EmployeeOwner employee) {
        List<String> states = jdbc.query("SELECT state FROM control_personal_memory_identity_link WHERE id = ? FOR UPDATE",
                (rs, row) -> rs.getString(1), linkId);
        if (states.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "identity link not found");
        if (!"ACTIVE".equals(states.get(0))) throw new ResponseStatusException(HttpStatus.CONFLICT, "identity link is not active");
        if (employee != null) {
            long bindingId = employeeBindingId(employee);
            var candidates = jdbc.query("""
                    SELECT tenant_id, left_binding_id, right_binding_id, left_business_user_id, right_business_user_id,
                           left_app_id, right_app_id, left_external_user_id, right_external_user_id
                    FROM control_personal_memory_identity_link WHERE id=? AND active_marker=1
                    """, (rs, row) -> {
                boolean left = rs.getLong("left_binding_id") == bindingId;
                boolean right = rs.getLong("right_binding_id") == bindingId;
                String side = left ? "left" : "right";
                return (left || right) && employee.tenantId().equals(rs.getString("tenant_id"))
                        && employee.businessUserId() == rs.getLong(side + "_business_user_id")
                        && employee.appId().equals(rs.getString(side + "_app_id"))
                        && employee.externalUserId().equals(rs.getString(side + "_external_user_id"));
            }, linkId);
            Account account = lockedAccount(bindingId);
            if (candidates.size() != 1 || !candidates.get(0) || !matches(account, employee)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "identity link not found");
            }
            actorId = "employee:" + bindingId;
        } else if ("SELF".equals(method)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "verified employee identity is required");
        }
        LocalDateTime now = LocalDateTime.now();
        int updated = jdbc.update("UPDATE control_personal_memory_identity_link SET state='REVOKED', active_marker=NULL, updated_at=? WHERE id=? AND state='ACTIVE' AND active_marker=1", now, linkId);
        if (updated != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "identity link changed");
        event(linkId, "REVOKE", method, actorId, note, now);
    }

    private Account lockedAccount(long id) {
        List<Account> rows = jdbc.query("""
                SELECT a.id, a.tenant_id, a.business_user_id, a.app_id, a.external_user_id, a.status,
                       u.tenant_id AS owner_tenant, u.status AS owner_status
                FROM control_external_user_binding a JOIN control_business_user u ON u.id=a.business_user_id
                WHERE a.id=? FOR UPDATE
                """, (rs, row) -> new Account(rs.getLong("id"), rs.getString("tenant_id"),
                rs.getLong("business_user_id"), rs.getString("app_id"), rs.getString("external_user_id"),
                rs.getString("status"), rs.getString("owner_tenant"), rs.getString("owner_status")), id);
        if (rows.size() != 1) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "business identity not found");
        Account account = rows.get(0);
        if (!StringUtils.hasText(account.tenant()) || !StringUtils.hasText(account.app())
                || !StringUtils.hasText(account.user()) || !account.tenant().equals(account.ownerTenant())
                || !"ACTIVE".equalsIgnoreCase(account.status()) || !"ACTIVE".equalsIgnoreCase(account.ownerStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "business identity is not active or has an invalid owner");
        }
        return account;
    }

    private void event(long id, String action, String method, String actor, String note, LocalDateTime now) {
        jdbc.update("INSERT INTO control_personal_memory_identity_link_event (link_id, action, verification_method, actor, verification_note, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                id, action, method, actor, note, now);
    }

    private static String requireAdministrator(PlatformAuthenticatedSession actor) {
        if (actor == null || actor.user() == null || actor.user().getId() == null || actor.user().getId() <= 0
                || !actor.hasGlobalPermission(PlatformPermissions.BUSINESS_USER_MANAGE)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "global business identity management permission is required");
        }
        return "platform:" + actor.user().getId();
    }

    private static String requireEvidence(String evidence) {
        String value = evidence == null ? "" : evidence.trim();
        if (value.length() < 4 || value.length() > 512) throw invalid("verification evidence must contain 4 to 512 characters");
        return value;
    }

    private static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private record Account(long id, String tenant, long ownerId, String app, String user,
                           String status, String ownerTenant, String ownerStatus) { }
}
