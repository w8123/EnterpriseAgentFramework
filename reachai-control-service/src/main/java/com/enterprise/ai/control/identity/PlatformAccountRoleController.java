package com.enterprise.ai.control.identity;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.enterprise.ai.control.client.capability.CapabilityProjectOnboardingClient;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Enterprise account, role, permission and authorization-audit workbench API.
 *
 * <p>The compatibility identity controller keeps login and the original role
 * assignment endpoints. This controller owns the richer administration
 * contract so account lifecycle and custom-role behavior stay explicit and
 * independently testable.</p>
 */
@RestController
@RequiredArgsConstructor
public class PlatformAccountRoleController {

    private static final Set<String> SYSTEM_ROLE_CODES = Set.of(
            "PLATFORM_ADMIN", "AGENT_DESIGNER", "PROJECT_OWNER", "OPERATOR", "AUDITOR");
    private static final Set<String> RESERVED_CUSTOM_PERMISSIONS = Set.of(
            PlatformPermissions.ALL, PlatformPermissions.PLATFORM_ADMIN);
    private static final Pattern USERNAME_PATTERN = Pattern.compile("[A-Za-z][A-Za-z0-9._-]{2,95}");
    private static final Pattern ROLE_CODE_PATTERN = Pattern.compile("[A-Z][A-Z0-9_]{2,63}");

    private final PlatformUserMapper userMapper;
    private final PlatformRoleMapper roleMapper;
    private final PlatformUserRoleMapper userRoleMapper;
    private final PlatformPermissionMapper permissionMapper;
    private final PlatformRolePermissionMapper rolePermissionMapper;
    private final PlatformLoginSessionMapper sessionMapper;
    private final PlatformAuthAuditEventMapper auditEventMapper;
    private final PlatformRequestAuthorization requestAuthorization;
    private final PlatformAuthAuditService auditService;
    private final PasswordEncoder passwordEncoder;
    private final CapabilityProjectOnboardingClient projectClient;

    @GetMapping("/api/platform/account-management/users")
    public ResponseEntity<List<AccountUserView>> listUsers(HttpServletRequest request) {
        requireAdmin(request);
        return ResponseEntity.ok(accountUserViews());
    }

    @PostMapping("/api/platform/account-management/users")
    @Transactional
    public ResponseEntity<AccountUserView> createUser(
            HttpServletRequest request,
            @RequestBody AccountUserCreateCommand command) {
        PlatformAuthenticatedSession actor = requireAdmin(request);
        if (command == null) throw badRequest("account command is required");
        String username = normalizeUsername(command.username());
        if (userMapper.selectOne(new LambdaQueryWrapper<PlatformUserEntity>()
                .eq(PlatformUserEntity::getUsername, username)
                .last("limit 1")) != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "platform username already exists");
        }
        String password = validatePassword(command.password());
        List<NormalizedGrant> grants = normalizeGrants(command.grants());
        LocalDateTime now = LocalDateTime.now();
        PlatformUserEntity entity = new PlatformUserEntity();
        entity.setUsername(username);
        entity.setDisplayName(requireLength(command.displayName(), "displayName", 128));
        entity.setEmail(optionalLength(command.email(), "email", 128));
        entity.setMobile(optionalLength(command.mobile(), "mobile", 64));
        entity.setStatus(normalizeStatus(command.status(), "ACTIVE"));
        entity.setSourceProvider("LOCAL");
        entity.setExternalSubject("local:" + username);
        entity.setPasswordHash(passwordEncoder.encode(password));
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        userMapper.insert(entity);
        replaceGrants(entity.getId(), grants, now);
        auditService.record(actor, "PLATFORM_ACCOUNT_CREATED", "PLATFORM_USER",
                String.valueOf(entity.getId()), Map.of(
                        "username", username,
                        "sourceProvider", "LOCAL",
                        "status", entity.getStatus(),
                        "grantCount", grants.size()));
        return ResponseEntity.status(HttpStatus.CREATED).body(accountUserView(entity));
    }

    @PutMapping("/api/platform/account-management/users/{userId}")
    @Transactional
    public ResponseEntity<AccountUserView> updateUser(
            HttpServletRequest request,
            @PathVariable Long userId,
            @RequestBody AccountUserUpdateCommand command) {
        PlatformAuthenticatedSession actor = requireAdmin(request);
        PlatformUserEntity entity = requireUser(userId);
        if (command == null) throw badRequest("account command is required");
        String nextStatus = normalizeStatus(command.status(), entity.getStatus());
        if (!"ACTIVE".equals(nextStatus) && actor.user() != null
                && Objects.equals(actor.user().getId(), entity.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "the current account cannot deactivate itself");
        }
        if (!"ACTIVE".equals(nextStatus) && "ACTIVE".equalsIgnoreCase(entity.getStatus())) {
            ensureNotFinalAdministrator(entity.getId());
        }
        String previousStatus = entity.getStatus();
        entity.setDisplayName(requireLength(command.displayName(), "displayName", 128));
        entity.setEmail(optionalLength(command.email(), "email", 128));
        entity.setMobile(optionalLength(command.mobile(), "mobile", 64));
        entity.setStatus(nextStatus);
        entity.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(entity);
        int revokedSessions = 0;
        if (!"ACTIVE".equals(nextStatus)) revokedSessions = revokeSessions(entity.getId());
        auditService.record(actor, "PLATFORM_ACCOUNT_UPDATED", "PLATFORM_USER",
                String.valueOf(entity.getId()), Map.of(
                        "previousStatus", previousStatus,
                        "status", nextStatus,
                        "revokedSessions", revokedSessions));
        return ResponseEntity.ok(accountUserView(entity));
    }

    @PostMapping("/api/platform/account-management/users/{userId}/password-reset")
    @Transactional
    public ResponseEntity<SessionRevocationView> resetPassword(
            HttpServletRequest request,
            @PathVariable Long userId,
            @RequestBody PasswordResetCommand command) {
        PlatformAuthenticatedSession actor = requireAdmin(request);
        PlatformUserEntity entity = requireUser(userId);
        if (!"LOCAL".equalsIgnoreCase(entity.getSourceProvider())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "password reset is available only for LOCAL accounts");
        }
        String password = validatePassword(command == null ? null : command.password());
        entity.setPasswordHash(passwordEncoder.encode(password));
        entity.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(entity);
        int revoked = revokeSessions(userId);
        auditService.record(actor, "PLATFORM_ACCOUNT_PASSWORD_RESET", "PLATFORM_USER",
                String.valueOf(userId), Map.of("revokedSessions", revoked));
        return ResponseEntity.ok(new SessionRevocationView(revoked));
    }

    @PostMapping("/api/platform/account-management/users/{userId}/sessions/revoke")
    @Transactional
    public ResponseEntity<SessionRevocationView> revokeUserSessions(
            HttpServletRequest request,
            @PathVariable Long userId) {
        PlatformAuthenticatedSession actor = requireAdmin(request);
        requireUser(userId);
        int revoked = revokeSessions(userId);
        auditService.record(actor, "PLATFORM_ACCOUNT_SESSIONS_REVOKED", "PLATFORM_USER",
                String.valueOf(userId), Map.of("revokedSessions", revoked));
        return ResponseEntity.ok(new SessionRevocationView(revoked));
    }

    @PutMapping("/api/platform/account-management/users/{userId}/grants")
    @Transactional
    public ResponseEntity<List<PlatformIdentityController.PlatformUserRoleGrantView>> saveUserGrants(
            HttpServletRequest request,
            @PathVariable Long userId,
            @RequestBody List<RoleGrantCommand> commands) {
        PlatformAuthenticatedSession actor = requireAdmin(request);
        PlatformUserEntity user = requireUser(userId);
        if (!"ACTIVE".equalsIgnoreCase(user.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "role grants can be changed only for an ACTIVE account");
        }
        List<NormalizedGrant> grants = normalizeGrants(commands);
        ensureAdministratorGrantInvariant(userId, grants);
        replaceGrants(userId, grants, LocalDateTime.now());
        auditService.record(actor, "PLATFORM_USER_ROLE_GRANTS_REPLACED", "PLATFORM_USER",
                String.valueOf(userId), Map.of("grantCount", grants.size()));
        return ResponseEntity.ok(grantViews(userId));
    }

    @GetMapping("/api/platform/account-management/permissions")
    public ResponseEntity<List<PermissionView>> listPermissions(HttpServletRequest request) {
        requireAdmin(request);
        return ResponseEntity.ok(permissionMapper.selectList(new LambdaQueryWrapper<PlatformPermissionEntity>()
                        .orderByAsc(PlatformPermissionEntity::getResourceType)
                        .orderByAsc(PlatformPermissionEntity::getPermissionCode))
                .stream().map(this::permissionView).toList());
    }

    @GetMapping("/api/platform/account-management/roles")
    public ResponseEntity<List<RoleManagementView>> listRoles(HttpServletRequest request) {
        requireAdmin(request);
        return ResponseEntity.ok(roleManagementViews());
    }

    @PostMapping("/api/platform/account-management/roles")
    @Transactional
    public ResponseEntity<RoleManagementView> createRole(
            HttpServletRequest request,
            @RequestBody RoleCreateCommand command) {
        PlatformAuthenticatedSession actor = requireAdmin(request);
        if (command == null) throw badRequest("role command is required");
        String roleCode = normalizeRoleCode(command.roleCode());
        if (SYSTEM_ROLE_CODES.contains(roleCode)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "system role code is reserved");
        }
        if (roleMapper.selectOne(new LambdaQueryWrapper<PlatformRoleEntity>()
                .eq(PlatformRoleEntity::getRoleCode, roleCode)
                .last("limit 1")) != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "role code already exists");
        }
        String status = normalizeStatus(command.status(), "ACTIVE");
        List<PlatformPermissionEntity> permissions = normalizeCustomPermissions(command.permissionIds(), status);
        LocalDateTime now = LocalDateTime.now();
        PlatformRoleEntity role = new PlatformRoleEntity();
        role.setRoleCode(roleCode);
        role.setRoleName(requireLength(command.roleName(), "roleName", 128));
        role.setDescription(optionalLength(command.description(), "description", 512));
        role.setRoleKind("CUSTOM");
        role.setStatus(status);
        role.setCreatedAt(now);
        role.setUpdatedAt(now);
        roleMapper.insert(role);
        replaceRolePermissions(role.getId(), permissions);
        auditService.record(actor, "PLATFORM_CUSTOM_ROLE_CREATED", "PLATFORM_ROLE",
                String.valueOf(role.getId()), Map.of(
                        "roleCode", roleCode,
                        "status", status,
                        "permissionCount", permissions.size()));
        return ResponseEntity.status(HttpStatus.CREATED).body(roleManagementView(role));
    }

    @PutMapping("/api/platform/account-management/roles/{roleId}")
    @Transactional
    public ResponseEntity<RoleManagementView> updateRole(
            HttpServletRequest request,
            @PathVariable Long roleId,
            @RequestBody RoleUpdateCommand command) {
        PlatformAuthenticatedSession actor = requireAdmin(request);
        PlatformRoleEntity role = requireRole(roleId);
        if (isSystemRole(role)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "system role definitions are immutable");
        }
        if (command == null) throw badRequest("role command is required");
        String status = normalizeStatus(command.status(), role.getStatus());
        long grantCount = userRoleMapper.selectCount(new LambdaQueryWrapper<PlatformUserRoleEntity>()
                .eq(PlatformUserRoleEntity::getRoleId, roleId));
        if (!"ACTIVE".equals(status) && grantCount > 0 && !Boolean.TRUE.equals(command.acknowledgeAssignedUsers())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "deactivating an assigned role requires impact acknowledgement");
        }
        List<PlatformPermissionEntity> permissions = normalizeCustomPermissions(command.permissionIds(), status);
        role.setRoleName(requireLength(command.roleName(), "roleName", 128));
        role.setDescription(optionalLength(command.description(), "description", 512));
        role.setRoleKind("CUSTOM");
        role.setStatus(status);
        role.setUpdatedAt(LocalDateTime.now());
        roleMapper.updateById(role);
        replaceRolePermissions(roleId, permissions);
        auditService.record(actor, "PLATFORM_CUSTOM_ROLE_UPDATED", "PLATFORM_ROLE",
                String.valueOf(roleId), Map.of(
                        "roleCode", role.getRoleCode(),
                        "status", status,
                        "permissionCount", permissions.size(),
                        "assignedGrantCount", grantCount));
        return ResponseEntity.ok(roleManagementView(role));
    }

    @GetMapping("/api/platform/account-management/audit-events")
    public ResponseEntity<List<AuthAuditEventView>> listAuditEvents(
            HttpServletRequest request,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String targetType,
            @RequestParam(defaultValue = "100") int limit) {
        requireAdmin(request);
        int safeLimit = Math.max(1, Math.min(limit, 500));
        LambdaQueryWrapper<PlatformAuthAuditEventEntity> query =
                new LambdaQueryWrapper<PlatformAuthAuditEventEntity>()
                        .orderByDesc(PlatformAuthAuditEventEntity::getId)
                        .last("limit " + safeLimit);
        if (StringUtils.hasText(eventType)) {
            query.eq(PlatformAuthAuditEventEntity::getEventType, text(eventType));
        }
        if (StringUtils.hasText(targetType)) {
            query.eq(PlatformAuthAuditEventEntity::getTargetType, text(targetType));
        }
        List<PlatformAuthAuditEventEntity> events = auditEventMapper.selectList(query);
        Set<Long> actorIds = events.stream().map(PlatformAuthAuditEventEntity::getActorUserId)
                .filter(Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, String> actors = actorIds.isEmpty() ? Map.of() : userMapper.selectBatchIds(actorIds).stream()
                .collect(Collectors.toMap(PlatformUserEntity::getId, PlatformUserEntity::getUsername));
        return ResponseEntity.ok(events.stream().map(event -> new AuthAuditEventView(
                event.getId(), event.getEventType(), event.getActorUserId(), actors.get(event.getActorUserId()),
                event.getTargetType(), event.getTargetId(), event.getDetailsJson(),
                instantText(event.getCreatedAt()))).toList());
    }

    private List<AccountUserView> accountUserViews() {
        List<PlatformUserEntity> users = userMapper.selectList(
                new LambdaQueryWrapper<PlatformUserEntity>().orderByDesc(PlatformUserEntity::getId));
        if (users.isEmpty()) return List.of();
        List<Long> userIds = users.stream().map(PlatformUserEntity::getId).toList();
        List<PlatformUserRoleEntity> grants = userRoleMapper.selectList(
                new LambdaQueryWrapper<PlatformUserRoleEntity>()
                        .in(PlatformUserRoleEntity::getUserId, userIds)
                        .orderByAsc(PlatformUserRoleEntity::getRoleId));
        Set<Long> roleIds = grants.stream().map(PlatformUserRoleEntity::getRoleId)
                .filter(Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, PlatformRoleEntity> roles = roleIds.isEmpty() ? Map.of()
                : roleMapper.selectBatchIds(roleIds).stream()
                        .collect(Collectors.toMap(PlatformRoleEntity::getId, Function.identity()));
        Map<Long, List<PlatformUserRoleEntity>> grantsByUser = grants.stream()
                .collect(Collectors.groupingBy(PlatformUserRoleEntity::getUserId));
        Map<Long, Long> activeSessionsByUser = sessionMapper.selectList(
                        new LambdaQueryWrapper<PlatformLoginSessionEntity>()
                                .in(PlatformLoginSessionEntity::getUserId, userIds)
                                .isNull(PlatformLoginSessionEntity::getRevokedAt)
                                .gt(PlatformLoginSessionEntity::getExpiresAt, LocalDateTime.now()))
                .stream().collect(Collectors.groupingBy(
                        PlatformLoginSessionEntity::getUserId, Collectors.counting()));
        return users.stream().map(user -> accountUserView(
                user,
                activeSessionsByUser.getOrDefault(user.getId(), 0L),
                grantViews(grantsByUser.getOrDefault(user.getId(), List.of()), roles))).toList();
    }

    private AccountUserView accountUserView(PlatformUserEntity user) {
        LocalDateTime now = LocalDateTime.now();
        long activeSessions = sessionMapper.selectCount(new LambdaQueryWrapper<PlatformLoginSessionEntity>()
                .eq(PlatformLoginSessionEntity::getUserId, user.getId())
                .isNull(PlatformLoginSessionEntity::getRevokedAt)
                .gt(PlatformLoginSessionEntity::getExpiresAt, now));
        return accountUserView(user, activeSessions, grantViews(user.getId()));
    }

    private AccountUserView accountUserView(
            PlatformUserEntity user,
            long activeSessions,
            List<PlatformIdentityController.PlatformUserRoleGrantView> grants) {
        return new AccountUserView(
                user.getId(), user.getUsername(), user.getDisplayName(), user.getEmail(), user.getMobile(),
                user.getStatus(), user.getSourceProvider(), instantText(user.getLastLoginAt()),
                instantText(user.getCreatedAt()), instantText(user.getUpdatedAt()), activeSessions,
                grants);
    }

    private List<RoleManagementView> roleManagementViews() {
        List<PlatformRoleEntity> roles = roleMapper.selectList(
                new LambdaQueryWrapper<PlatformRoleEntity>().orderByAsc(PlatformRoleEntity::getId));
        if (roles.isEmpty()) return List.of();
        List<Long> roleIds = roles.stream().map(PlatformRoleEntity::getId).toList();
        List<PlatformRolePermissionEntity> bindings = rolePermissionMapper.selectList(
                new LambdaQueryWrapper<PlatformRolePermissionEntity>()
                        .in(PlatformRolePermissionEntity::getRoleId, roleIds));
        Set<Long> permissionIds = bindings.stream().map(PlatformRolePermissionEntity::getPermissionId)
                .filter(Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, PlatformPermissionEntity> permissions = permissionIds.isEmpty() ? Map.of()
                : permissionMapper.selectBatchIds(permissionIds).stream()
                        .collect(Collectors.toMap(PlatformPermissionEntity::getId, Function.identity()));
        Map<Long, List<PlatformRolePermissionEntity>> bindingsByRole = bindings.stream()
                .collect(Collectors.groupingBy(PlatformRolePermissionEntity::getRoleId));
        Map<Long, List<PlatformUserRoleEntity>> grantsByRole = userRoleMapper.selectList(
                        new LambdaQueryWrapper<PlatformUserRoleEntity>()
                                .in(PlatformUserRoleEntity::getRoleId, roleIds))
                .stream().collect(Collectors.groupingBy(PlatformUserRoleEntity::getRoleId));
        return roles.stream().map(role -> roleManagementView(
                role,
                bindingsByRole.getOrDefault(role.getId(), List.of()),
                permissions,
                grantsByRole.getOrDefault(role.getId(), List.of()))).toList();
    }

    private RoleManagementView roleManagementView(PlatformRoleEntity role) {
        List<PlatformRolePermissionEntity> bindings = rolePermissionMapper.selectList(
                new LambdaQueryWrapper<PlatformRolePermissionEntity>()
                        .eq(PlatformRolePermissionEntity::getRoleId, role.getId()));
        List<Long> permissionIds = bindings.stream()
                .map(PlatformRolePermissionEntity::getPermissionId).distinct().toList();
        Map<Long, PlatformPermissionEntity> permissions = permissionIds.isEmpty() ? Map.of()
                : permissionMapper.selectBatchIds(permissionIds).stream()
                        .collect(Collectors.toMap(PlatformPermissionEntity::getId, Function.identity()));
        List<PlatformUserRoleEntity> grants = userRoleMapper.selectList(
                new LambdaQueryWrapper<PlatformUserRoleEntity>()
                        .eq(PlatformUserRoleEntity::getRoleId, role.getId()));
        return roleManagementView(role, bindings, permissions, grants);
    }

    private RoleManagementView roleManagementView(
            PlatformRoleEntity role,
            List<PlatformRolePermissionEntity> bindings,
            Map<Long, PlatformPermissionEntity> permissions,
            List<PlatformUserRoleEntity> grants) {
        List<PlatformPermissionEntity> ordered = bindings.stream()
                .map(PlatformRolePermissionEntity::getPermissionId)
                .distinct()
                .map(permissions::get)
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(PlatformPermissionEntity::getPermissionCode)).toList();
        long userCount = grants.stream().map(PlatformUserRoleEntity::getUserId).distinct().count();
        return new RoleManagementView(
                role.getId(), role.getRoleCode(), role.getRoleName(), role.getDescription(), roleKind(role),
                role.getStatus(), ordered.stream().map(PlatformPermissionEntity::getId).toList(),
                ordered.stream().map(PlatformPermissionEntity::getPermissionCode).toList(),
                userCount, grants.size(), instantText(role.getCreatedAt()), instantText(role.getUpdatedAt()));
    }

    private PermissionView permissionView(PlatformPermissionEntity permission) {
        return new PermissionView(permission.getId(), permission.getPermissionCode(), permission.getPermissionName(),
                permission.getResourceType(), permission.getAction(), permission.getDescription(),
                RESERVED_CUSTOM_PERMISSIONS.contains(permission.getPermissionCode()));
    }

    private List<PlatformIdentityController.PlatformUserRoleGrantView> grantViews(Long userId) {
        List<PlatformUserRoleEntity> grants = userRoleMapper.selectList(
                new LambdaQueryWrapper<PlatformUserRoleEntity>()
                        .eq(PlatformUserRoleEntity::getUserId, userId)
                        .orderByAsc(PlatformUserRoleEntity::getRoleId));
        if (grants.isEmpty()) return List.of();
        Map<Long, PlatformRoleEntity> roles = roleMapper.selectBatchIds(grants.stream()
                        .map(PlatformUserRoleEntity::getRoleId).distinct().toList()).stream()
                .collect(Collectors.toMap(PlatformRoleEntity::getId, Function.identity()));
        return grantViews(grants, roles);
    }

    private List<PlatformIdentityController.PlatformUserRoleGrantView> grantViews(
            List<PlatformUserRoleEntity> grants,
            Map<Long, PlatformRoleEntity> roles) {
        return grants.stream().map(grant -> {
            PlatformRoleEntity role = roles.get(grant.getRoleId());
            return new PlatformIdentityController.PlatformUserRoleGrantView(
                    grant.getId(), grant.getRoleId(), role == null ? null : role.getRoleCode(),
                    role == null ? null : role.getRoleName(), grant.getScopeType(), grant.getScopeValue());
        }).toList();
    }

    private List<NormalizedGrant> normalizeGrants(List<RoleGrantCommand> commands) {
        List<RoleGrantCommand> requested = commands == null ? List.of() : commands;
        if (requested.isEmpty()) return List.of();
        if (requested.stream().anyMatch(command -> command == null || command.roleId() == null)) {
            throw badRequest("every role grant requires roleId");
        }
        Set<Long> roleIds = requested.stream().map(RoleGrantCommand::roleId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, PlatformRoleEntity> roles = roleMapper.selectBatchIds(roleIds).stream()
                .collect(Collectors.toMap(PlatformRoleEntity::getId, Function.identity()));
        if (roles.size() != roleIds.size()) throw badRequest("all roleIds must exist");
        List<NormalizedGrant> normalized = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>();
        Map<Long, Set<String>> roleScopes = new LinkedHashMap<>();
        for (RoleGrantCommand command : requested) {
            PlatformRoleEntity role = roles.get(command.roleId());
            if (!"ACTIVE".equalsIgnoreCase(role.getStatus())) throw badRequest("role must be ACTIVE");
            String scopeType = text(command.scopeType()).toUpperCase(Locale.ROOT);
            if (!Set.of("GLOBAL", "PROJECT").contains(scopeType)) throw badRequest("unsupported scopeType");
            String scopeValue = "GLOBAL".equals(scopeType) ? "*" : text(command.scopeValue());
            if ("PROJECT".equals(scopeType)) validateProjectScope(scopeValue);
            if ("PLATFORM_ADMIN".equalsIgnoreCase(role.getRoleCode())
                    && !("GLOBAL".equals(scopeType) && "*".equals(scopeValue))) {
                throw badRequest("PLATFORM_ADMIN must use GLOBAL/* scope");
            }
            Set<String> scopes = roleScopes.computeIfAbsent(command.roleId(), ignored -> new LinkedHashSet<>());
            scopes.add(scopeType);
            if (scopes.size() > 1) throw badRequest("one role cannot be both GLOBAL and PROJECT scoped");
            String key = command.roleId() + "|" + scopeType + "|" + scopeValue;
            if (!keys.add(key)) throw badRequest("duplicate role grant");
            normalized.add(new NormalizedGrant(command.roleId(), scopeType, scopeValue));
        }
        return List.copyOf(normalized);
    }

    private void validateProjectScope(String projectCode) {
        if (!StringUtils.hasText(projectCode) || "*".equals(projectCode)) {
            throw badRequest("PROJECT scope requires projectCode");
        }
        Map<String, Object> project = projectClient.getProjectByCode(projectCode);
        String canonicalCode = project == null ? null : text(project.get("projectCode"));
        if (!StringUtils.hasText(canonicalCode) || !projectCode.equals(canonicalCode)) {
            throw badRequest("PROJECT scope must reference an existing canonical projectCode");
        }
    }

    private List<PlatformPermissionEntity> normalizeCustomPermissions(List<Long> ids, String roleStatus) {
        Set<Long> requested = ids == null ? Set.of() : ids.stream().filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<PlatformPermissionEntity> permissions = requested.isEmpty() ? List.of()
                : permissionMapper.selectBatchIds(requested);
        if (permissions.size() != requested.size()) throw badRequest("all permissionIds must exist");
        List<String> reserved = permissions.stream().map(PlatformPermissionEntity::getPermissionCode)
                .filter(RESERVED_CUSTOM_PERMISSIONS::contains).toList();
        if (!reserved.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "custom roles cannot contain reserved platform administrator permissions");
        }
        if ("ACTIVE".equals(roleStatus) && permissions.isEmpty()) {
            throw badRequest("an ACTIVE custom role requires at least one permission");
        }
        return permissions;
    }

    private void replaceRolePermissions(Long roleId, List<PlatformPermissionEntity> permissions) {
        rolePermissionMapper.delete(new LambdaQueryWrapper<PlatformRolePermissionEntity>()
                .eq(PlatformRolePermissionEntity::getRoleId, roleId));
        for (PlatformPermissionEntity permission : permissions) {
            PlatformRolePermissionEntity binding = new PlatformRolePermissionEntity();
            binding.setRoleId(roleId);
            binding.setPermissionId(permission.getId());
            rolePermissionMapper.insert(binding);
        }
    }

    private void replaceGrants(Long userId, List<NormalizedGrant> grants, LocalDateTime createdAt) {
        userRoleMapper.delete(new LambdaQueryWrapper<PlatformUserRoleEntity>()
                .eq(PlatformUserRoleEntity::getUserId, userId));
        for (NormalizedGrant grant : grants) {
            PlatformUserRoleEntity entity = new PlatformUserRoleEntity();
            entity.setUserId(userId);
            entity.setRoleId(grant.roleId());
            entity.setScopeType(grant.scopeType());
            entity.setScopeValue(grant.scopeValue());
            entity.setCreatedAt(createdAt);
            userRoleMapper.insert(entity);
        }
    }

    private void ensureAdministratorGrantInvariant(Long userId, List<NormalizedGrant> grants) {
        PlatformRoleEntity admin = roleMapper.selectOne(new LambdaQueryWrapper<PlatformRoleEntity>()
                .eq(PlatformRoleEntity::getRoleCode, "PLATFORM_ADMIN").last("limit 1"));
        if (admin == null || admin.getId() == null) throw new IllegalStateException("PLATFORM_ADMIN role is required");
        boolean remainsAdmin = grants.stream().anyMatch(grant -> admin.getId().equals(grant.roleId())
                && "GLOBAL".equals(grant.scopeType()) && "*".equals(grant.scopeValue()));
        if (remainsAdmin) return;
        List<PlatformUserRoleEntity> activeAdmins = userRoleMapper
                .selectActiveGlobalPlatformAdministratorGrantsForUpdate();
        boolean targetWasAdmin = activeAdmins.stream().anyMatch(grant -> Objects.equals(userId, grant.getUserId()));
        if (targetWasAdmin && activeAdmins.stream().map(PlatformUserRoleEntity::getUserId).distinct().count() <= 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "cannot remove the final ACTIVE global PLATFORM_ADMIN");
        }
    }

    private void ensureNotFinalAdministrator(Long userId) {
        List<PlatformUserRoleEntity> activeAdmins = userRoleMapper
                .selectActiveGlobalPlatformAdministratorGrantsForUpdate();
        boolean targetIsAdmin = activeAdmins.stream().anyMatch(grant -> Objects.equals(userId, grant.getUserId()));
        if (targetIsAdmin && activeAdmins.stream().map(PlatformUserRoleEntity::getUserId).distinct().count() <= 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "cannot deactivate the final ACTIVE global PLATFORM_ADMIN");
        }
    }

    private int revokeSessions(Long userId) {
        return sessionMapper.update(null, new LambdaUpdateWrapper<PlatformLoginSessionEntity>()
                .eq(PlatformLoginSessionEntity::getUserId, userId)
                .isNull(PlatformLoginSessionEntity::getRevokedAt)
                .set(PlatformLoginSessionEntity::getRevokedAt, LocalDateTime.now()));
    }

    private PlatformAuthenticatedSession requireAdmin(HttpServletRequest request) {
        return requestAuthorization.requireGlobalPermission(request, PlatformPermissions.PLATFORM_ADMIN);
    }

    private PlatformUserEntity requireUser(Long userId) {
        PlatformUserEntity user = userId == null ? null : userMapper.selectById(userId);
        if (user == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "platform account not found");
        return user;
    }

    private PlatformRoleEntity requireRole(Long roleId) {
        PlatformRoleEntity role = roleId == null ? null : roleMapper.selectById(roleId);
        if (role == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "platform role not found");
        return role;
    }

    private boolean isSystemRole(PlatformRoleEntity role) {
        return "SYSTEM".equalsIgnoreCase(roleKind(role)) || SYSTEM_ROLE_CODES.contains(role.getRoleCode());
    }

    private String roleKind(PlatformRoleEntity role) {
        if (StringUtils.hasText(role.getRoleKind())) return role.getRoleKind().trim().toUpperCase(Locale.ROOT);
        return SYSTEM_ROLE_CODES.contains(role.getRoleCode()) ? "SYSTEM" : "CUSTOM";
    }

    private String normalizeUsername(String value) {
        String username = text(value);
        if (!USERNAME_PATTERN.matcher(username).matches()) {
            throw badRequest("username must start with a letter and use 3-96 letters, digits, dot, underscore or hyphen");
        }
        return username.toLowerCase(Locale.ROOT);
    }

    private String normalizeRoleCode(String value) {
        String code = text(value).toUpperCase(Locale.ROOT);
        if (!ROLE_CODE_PATTERN.matcher(code).matches()) {
            throw badRequest("roleCode must use 3-64 uppercase letters, digits or underscore");
        }
        return code;
    }

    private String validatePassword(String value) {
        String password = value == null ? "" : value;
        if (password.length() < 12 || password.length() > 128) {
            throw badRequest("password length must be between 12 and 128 characters");
        }
        int classes = 0;
        if (password.chars().anyMatch(Character::isLowerCase)) classes++;
        if (password.chars().anyMatch(Character::isUpperCase)) classes++;
        if (password.chars().anyMatch(Character::isDigit)) classes++;
        if (password.chars().anyMatch(valueChar -> !Character.isLetterOrDigit(valueChar))) classes++;
        if (classes < 3) throw badRequest("password must contain at least three character classes");
        return password;
    }

    private String normalizeStatus(String value, String fallback) {
        String status = StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : fallback;
        if (!Set.of("ACTIVE", "INACTIVE").contains(status)) throw badRequest("unsupported status");
        return status;
    }

    private String requireLength(String value, String field, int maxLength) {
        String normalized = text(value);
        if (normalized.length() > maxLength) throw badRequest(field + " is too long");
        return normalized;
    }

    private String optionalLength(String value, String field, int maxLength) {
        if (!StringUtils.hasText(value)) return null;
        return requireLength(value, field, maxLength);
    }

    private String text(Object value) {
        String normalized = value == null ? "" : String.valueOf(value).trim();
        if (normalized.isEmpty()) throw badRequest("required text is missing");
        return normalized;
    }

    private String instantText(LocalDateTime value) {
        return value == null ? null : value.atZone(ZoneId.systemDefault()).toInstant().toString();
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    public record AccountUserView(
            Long id, String username, String displayName, String email, String mobile,
            String status, String sourceProvider, String lastLoginAt, String createdAt,
            String updatedAt, long activeSessionCount,
            List<PlatformIdentityController.PlatformUserRoleGrantView> grants) {
    }

    public record AccountUserCreateCommand(
            String username, String displayName, String email, String mobile,
            String password, String status, List<RoleGrantCommand> grants) {
    }

    public record AccountUserUpdateCommand(
            String displayName, String email, String mobile, String status) {
    }

    public record PasswordResetCommand(String password) {
    }

    public record SessionRevocationView(int revokedSessions) {
    }

    public record RoleGrantCommand(Long roleId, String scopeType, String scopeValue) {
    }

    public record PermissionView(
            Long id, String permissionCode, String permissionName, String resourceType,
            String action, String description, boolean reservedForSystemRole) {
    }

    public record RoleManagementView(
            Long id, String roleCode, String roleName, String description, String roleKind,
            String status, List<Long> permissionIds, List<String> permissionCodes,
            long userCount, long grantCount, String createdAt, String updatedAt) {
    }

    public record RoleCreateCommand(
            String roleCode, String roleName, String description, String status,
            List<Long> permissionIds) {
    }

    public record RoleUpdateCommand(
            String roleName, String description, String status, List<Long> permissionIds,
            Boolean acknowledgeAssignedUsers) {
    }

    public record AuthAuditEventView(
            Long id, String eventType, Long actorUserId, String actorUsername,
            String targetType, String targetId, String detailsJson, String createdAt) {
    }

    private record NormalizedGrant(Long roleId, String scopeType, String scopeValue) {
    }
}
