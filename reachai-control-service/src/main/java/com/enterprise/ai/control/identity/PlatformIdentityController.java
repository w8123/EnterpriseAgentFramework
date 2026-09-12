package com.enterprise.ai.control.identity;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletRequest;

import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequiredArgsConstructor
public class PlatformIdentityController {

    public static final String AUTH_READINESS_HEADER = "X-ReachAI-Auth-Readiness";

    private final PlatformUserMapper userMapper;
    private final PlatformRoleMapper roleMapper;
    private final PlatformUserRoleMapper userRoleMapper;
    private final PlatformLoginSessionMapper sessionMapper;
    private final PlatformAuthProviderMapper authProviderMapper;
    private final PlatformAuthProperties authProperties;
    private final PlatformConsoleAuthAvailability consoleAuthAvailability;
    private final PlatformAuthorizationService authorizationService;
    private final PlatformRequestAuthorization requestAuthorization;
    private final PlatformAuthAuditService authAuditService;
    private final PlatformSessionTokenCodec sessionTokenCodec;
    private final PlatformSessionCookieService sessionCookieService;
    private final PasswordEncoder platformPasswordEncoder;

    @PostMapping("/api/platform/auth/login")
    public ResponseEntity<PlatformLoginResult> login(
            HttpServletRequest httpRequest,
            @RequestBody PlatformLoginRequest request) {
        String username = requireText(request == null ? null : request.username(), "username");
        String password = requireText(request == null ? null : request.password(), "password");
        PlatformConsoleAuthAvailability.Availability availability = consoleAuthAvailability.current();
        if (!availability.available()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header(AUTH_READINESS_HEADER, availability.code())
                    .build();
        }
        PlatformUserEntity user = userMapper.selectOne(new LambdaQueryWrapper<PlatformUserEntity>()
                .eq(PlatformUserEntity::getUsername, username)
                .last("limit 1"));
        if (user == null
                || !"LOCAL".equalsIgnoreCase(user.getSourceProvider())
                || !"ACTIVE".equalsIgnoreCase(user.getStatus())
                || !passwordMatches(user, password)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        user.setLastLoginAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(user);
        IssuedPlatformSession issuedSession = createSession(user);
        sessionMapper.insert(issuedSession.session());
        PlatformAuthenticatedSession authenticatedSession = authorizationService.authenticatedSession(
                user,
                issuedSession.session());
        return ResponseEntity.ok()
                .header("Set-Cookie", sessionCookieService.issueCookie(httpRequest, issuedSession.accessToken()))
                .header("Cache-Control", "no-store")
                .body(new PlatformLoginResult(
                        authProperties.getSessionTtl().toSeconds(),
                        instantText(issuedSession.session().getExpiresAt()),
                        issuedSession.session().getSessionId(),
                        toProfile(authenticatedSession)));
    }

    @GetMapping("/api/platform/auth/me")
    public ResponseEntity<PlatformSessionView> me(HttpServletRequest request) {
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(toSessionView(requireAuthenticatedSession(request)));
    }

    @PostMapping("/api/platform/auth/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        PlatformAuthenticatedSession session = requireAuthenticatedSession(request);
        sessionMapper.update(null, new LambdaUpdateWrapper<PlatformLoginSessionEntity>()
                .eq(PlatformLoginSessionEntity::getSessionId, session.sessionId())
                .isNull(PlatformLoginSessionEntity::getRevokedAt)
                .set(PlatformLoginSessionEntity::getRevokedAt, LocalDateTime.now()));
        return ResponseEntity.ok()
                .header("Set-Cookie", sessionCookieService.expireCookie(request))
                .header("Cache-Control", "no-store")
                .build();
    }

    @GetMapping("/api/platform/auth-providers")
    public ResponseEntity<List<PlatformAuthProviderView>> listAuthProviders(HttpServletRequest request) {
        requirePlatformAdmin(request);
        return ResponseEntity.ok(authProviderMapper.selectList(new LambdaQueryWrapper<PlatformAuthProviderEntity>()
                        .orderByAsc(PlatformAuthProviderEntity::getId))
                .stream()
                .map(this::toAuthProviderView)
                .toList());
    }

    @PostMapping("/api/platform/auth-providers")
    @Transactional
    public ResponseEntity<PlatformAuthProviderView> saveAuthProvider(
            HttpServletRequest httpRequest,
            @RequestBody PlatformAuthProviderCommand request) {
        PlatformAuthenticatedSession actor = requirePlatformAdmin(httpRequest);
        String providerCode = requireProviderCode(request == null ? null : request.providerCode(), "providerCode");
        String providerType = requireProviderCode(request == null ? null : request.providerType(), "providerType");
        if (!providerCode.equals(providerType)) {
            throw badRequest("providerCode must match providerType");
        }
        PlatformAuthProviderEntity entity = authProviderMapper.selectOne(new LambdaQueryWrapper<PlatformAuthProviderEntity>()
                .eq(PlatformAuthProviderEntity::getProviderCode, providerCode)
                .last("limit 1"));
        if (entity == null) {
            entity = new PlatformAuthProviderEntity();
            entity.setProviderCode(providerCode);
            entity.setCreatedAt(LocalDateTime.now());
        }
        entity.setProviderName(requireText(request.providerName(), "providerName"));
        entity.setProviderType(providerType);
        entity.setStatus(normalizeProviderStatus(providerType, request.status()));
        entity.setConfigJson(normalizeProviderConfig(providerType, request.configJson()));
        entity.setUpdatedAt(LocalDateTime.now());
        if (entity.getId() == null) {
            authProviderMapper.insert(entity);
        } else {
            authProviderMapper.updateById(entity);
        }
        authAuditService.record(
                actor,
                "PLATFORM_AUTH_PROVIDER_SAVED",
                "AUTH_PROVIDER",
                String.valueOf(entity.getId()),
                Map.of(
                        "providerCode", entity.getProviderCode(),
                        "providerType", entity.getProviderType(),
                        "status", entity.getStatus(),
                        "configurationPresent", hasNonEmptyProviderConfig(entity)));
        return ResponseEntity.ok(toAuthProviderView(entity));
    }

    @GetMapping("/api/platform/users")
    public ResponseEntity<List<PlatformUserView>> listUsers(HttpServletRequest request) {
        requirePlatformAdmin(request);
        return ResponseEntity.ok(userMapper.selectList(new LambdaQueryWrapper<PlatformUserEntity>()
                        .orderByDesc(PlatformUserEntity::getId))
                .stream()
                .map(this::toUserView)
                .toList());
    }

    @GetMapping("/api/platform/roles")
    public ResponseEntity<List<PlatformRoleView>> listRoles(HttpServletRequest request) {
        requirePlatformAdmin(request);
        return ResponseEntity.ok(roleMapper.selectList(new LambdaQueryWrapper<PlatformRoleEntity>()
                        .orderByAsc(PlatformRoleEntity::getId))
                .stream()
                .map(this::toRoleView)
                .toList());
    }

    @GetMapping("/api/platform/users/{userId}/roles")
    public ResponseEntity<List<PlatformUserRoleGrantView>> listUserRoleGrants(
            HttpServletRequest request,
            @PathVariable Long userId) {
        requirePlatformAdmin(request);
        return ResponseEntity.ok(roleGrantViews(userId));
    }

    @PutMapping("/api/platform/users/{userId}/roles")
    @Transactional
    public ResponseEntity<List<PlatformUserRoleGrantView>> saveUserRoleGrants(
            HttpServletRequest request,
            @PathVariable Long userId,
            @RequestBody List<PlatformUserRoleGrantCommand> commands) {
        PlatformAuthenticatedSession actor = requirePlatformAdmin(request);
        PlatformUserEntity targetUser = userMapper.selectById(userId);
        if (targetUser == null || !"ACTIVE".equalsIgnoreCase(targetUser.getStatus())) {
            throw badRequest("target user must exist and be ACTIVE");
        }
        List<NormalizedRoleGrantCommand> normalizedCommands = normalizeRoleGrantCommands(commands);
        ensureGlobalAdministratorInvariant(targetUser, normalizedCommands);
        userRoleMapper.delete(new LambdaQueryWrapper<PlatformUserRoleEntity>()
                .eq(PlatformUserRoleEntity::getUserId, userId));
        for (NormalizedRoleGrantCommand command : normalizedCommands) {
            PlatformUserRoleEntity entity = new PlatformUserRoleEntity();
            entity.setUserId(userId);
            entity.setRoleId(command.roleId());
            entity.setScopeType(command.scopeType());
            entity.setScopeValue(command.scopeValue());
            entity.setCreatedAt(LocalDateTime.now());
            userRoleMapper.insert(entity);
        }
        authAuditService.record(
                actor,
                "PLATFORM_USER_ROLE_GRANTS_REPLACED",
                "PLATFORM_USER",
                String.valueOf(userId),
                Map.of("grantCount", normalizedCommands.size()));
        return ResponseEntity.ok(roleGrantViews(userId));
    }

    private IssuedPlatformSession createSession(PlatformUserEntity user) {
        String accessToken = sessionTokenCodec.issueRawToken();
        PlatformLoginSessionEntity entity = new PlatformLoginSessionEntity();
        entity.setSessionId("pls_" + UUID.randomUUID());
        entity.setUserId(user.getId());
        entity.setProvider(user.getSourceProvider());
        entity.setAccessTokenId(sessionTokenCodec.digest(accessToken));
        entity.setExpiresAt(LocalDateTime.now().plus(authProperties.getSessionTtl()));
        entity.setCreatedAt(LocalDateTime.now());
        return new IssuedPlatformSession(entity, accessToken);
    }

    private boolean passwordMatches(PlatformUserEntity user, String password) {
        if (!StringUtils.hasText(user.getPasswordHash())) {
            return false;
        }
        if (user.getPasswordHash().startsWith("{plain}")) {
            String legacyPassword = user.getPasswordHash().substring("{plain}".length());
            boolean matches = MessageDigest.isEqual(
                    legacyPassword.getBytes(StandardCharsets.UTF_8),
                    password.getBytes(StandardCharsets.UTF_8));
            if (matches) {
                user.setPasswordHash(platformPasswordEncoder.encode(password));
            }
            return matches;
        }
        try {
            return platformPasswordEncoder.matches(password, user.getPasswordHash());
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private PlatformUserProfile toProfile(PlatformAuthenticatedSession session) {
        PlatformPrincipal user = session.user();
        return new PlatformUserProfile(
                user.getId(),
                user.getUsername(),
                user.getDisplayName(),
                session.roles(),
                session.permissions(),
                session.permissionGrants());
    }

    private PlatformSessionView toSessionView(PlatformAuthenticatedSession session) {
        return new PlatformSessionView(
                session.sessionId(),
                instantText(session.expiresAt()),
                toProfile(session));
    }

    private PlatformAuthenticatedSession requirePlatformAdmin(HttpServletRequest request) {
        return requestAuthorization.requireGlobalPermission(request, PlatformPermissions.PLATFORM_ADMIN);
    }

    private PlatformAuthenticatedSession requireAuthenticatedSession(HttpServletRequest request) {
        return requestAuthorization.requireAuthenticated(request);
    }

    private List<NormalizedRoleGrantCommand> normalizeRoleGrantCommands(
            List<PlatformUserRoleGrantCommand> commands) {
        List<PlatformUserRoleGrantCommand> requested = commands == null ? List.of() : commands;
        if (requested.isEmpty()) {
            return List.of();
        }
        if (requested.stream().anyMatch(command -> command == null || command.roleId() == null)) {
            throw badRequest("each role grant must have a roleId");
        }
        Set<Long> roleIds = requested.stream()
                .map(PlatformUserRoleGrantCommand::roleId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, PlatformRoleEntity> roles = roleMapper.selectBatchIds(roleIds).stream()
                .filter(role -> role.getId() != null)
                .collect(Collectors.toMap(PlatformRoleEntity::getId, role -> role));
        if (roles.size() != roleIds.size()) {
            throw badRequest("all roleIds must exist");
        }
        List<NormalizedRoleGrantCommand> result = new ArrayList<>();
        Set<String> grantKeys = new LinkedHashSet<>();
        for (PlatformUserRoleGrantCommand command : requested) {
            PlatformRoleEntity role = roles.get(command.roleId());
            if (!"ACTIVE".equalsIgnoreCase(role.getStatus())) {
                throw badRequest("role must be ACTIVE: " + role.getRoleCode());
            }
            String scopeType = normalizeScopeType(command.scopeType());
            String scopeValue = normalizeScopeValue(command.scopeValue(), scopeType);
            if ("PLATFORM_ADMIN".equalsIgnoreCase(role.getRoleCode())
                    && (!"GLOBAL".equals(scopeType) || !"*".equals(scopeValue))) {
                throw badRequest("PLATFORM_ADMIN must use GLOBAL/* scope");
            }
            String grantKey = command.roleId() + "|" + scopeType + "|" + scopeValue;
            if (!grantKeys.add(grantKey)) {
                throw badRequest("duplicate role grant: " + grantKey);
            }
            result.add(new NormalizedRoleGrantCommand(command.roleId(), scopeType, scopeValue));
        }
        return List.copyOf(result);
    }

    private void ensureGlobalAdministratorInvariant(
            PlatformUserEntity targetUser,
            List<NormalizedRoleGrantCommand> commands) {
        PlatformRoleEntity administratorRole = roleMapper.selectOne(new LambdaQueryWrapper<PlatformRoleEntity>()
                .eq(PlatformRoleEntity::getRoleCode, "PLATFORM_ADMIN")
                .last("limit 1"));
        if (administratorRole == null || administratorRole.getId() == null
                || !"ACTIVE".equalsIgnoreCase(administratorRole.getStatus())) {
            throw new IllegalStateException("ACTIVE PLATFORM_ADMIN role is required");
        }
        boolean targetRemainsAdministrator = commands.stream().anyMatch(command ->
                administratorRole.getId().equals(command.roleId())
                        && "GLOBAL".equals(command.scopeType())
                        && "*".equals(command.scopeValue()));
        if (targetRemainsAdministrator) {
            return;
        }

        // Locks every global admin grant until this transaction commits, so two
        // concurrent role updates cannot both remove the last administrator.
        List<PlatformUserRoleEntity> existingAdministratorGrants =
                userRoleMapper.selectGlobalRoleGrantsForUpdate(administratorRole.getId());
        Set<Long> otherAdministratorIds = existingAdministratorGrants.stream()
                .map(PlatformUserRoleEntity::getUserId)
                .filter(id -> id != null && !id.equals(targetUser.getId()))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        boolean anotherActiveAdministrator = !otherAdministratorIds.isEmpty()
                && userMapper.selectBatchIds(otherAdministratorIds).stream()
                .anyMatch(user -> "ACTIVE".equalsIgnoreCase(user.getStatus()));
        if (!anotherActiveAdministrator) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "cannot remove the final ACTIVE global PLATFORM_ADMIN");
        }
    }

    private String normalizeScopeType(String value) {
        String scopeType = StringUtils.hasText(value) ? value.trim().toUpperCase(java.util.Locale.ROOT) : "GLOBAL";
        if (!Set.of("GLOBAL", "PROJECT").contains(scopeType)) {
            throw badRequest("unsupported role scopeType: " + scopeType);
        }
        return scopeType;
    }

    private String normalizeScopeValue(String value, String scopeType) {
        String scopeValue = StringUtils.hasText(value) ? value.trim() : "*";
        if ("GLOBAL".equals(scopeType)) {
            if (!"*".equals(scopeValue)) {
                throw badRequest("GLOBAL role scopeValue must be *");
            }
            return scopeValue;
        }
        if (!StringUtils.hasText(scopeValue) || "*".equals(scopeValue)) {
            throw badRequest("PROJECT role scopeValue is required");
        }
        return scopeValue;
    }

    private String requireProviderCode(String value, String field) {
        String providerCode = requireText(value, field).toUpperCase(java.util.Locale.ROOT);
        if (!Set.of("LOCAL", "HEADER", "OIDC", "SAML").contains(providerCode)) {
            throw badRequest("unsupported provider: " + providerCode);
        }
        return providerCode;
    }

    private String normalizeProviderStatus(String providerType, String requestedStatus) {
        String status = StringUtils.hasText(requestedStatus)
                ? requestedStatus.trim().toUpperCase(java.util.Locale.ROOT)
                : "INACTIVE";
        if (!Set.of("ACTIVE", "INACTIVE").contains(status)) {
            throw badRequest("unsupported provider status: " + status);
        }
        if ("LOCAL".equals(providerType) && !"ACTIVE".equals(status)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "LOCAL is the only implemented provider and cannot be disabled by this release");
        }
        if (!"LOCAL".equals(providerType) && "ACTIVE".equals(status)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "provider is not implemented and cannot be ACTIVE: " + providerType);
        }
        return status;
    }

    private String normalizeProviderConfig(String providerType, String configJson) {
        String normalized = StringUtils.hasText(configJson) ? configJson.trim() : "{}";
        if (!"{}".equals(normalized)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "provider configuration with secrets is unavailable until encrypted provider support is implemented: "
                            + providerType);
        }
        return "{}";
    }

    private List<PlatformUserRoleGrantView> roleGrantViews(Long userId) {
        List<PlatformUserRoleEntity> grants = userRoleMapper.selectList(new LambdaQueryWrapper<PlatformUserRoleEntity>()
                .eq(PlatformUserRoleEntity::getUserId, userId));
        if (grants.isEmpty()) {
            return List.of();
        }
        List<Long> roleIds = grants.stream()
                .map(PlatformUserRoleEntity::getRoleId)
                .distinct()
                .toList();
        Map<Long, PlatformRoleEntity> roles = roleMapper.selectBatchIds(roleIds).stream()
                .collect(Collectors.toMap(PlatformRoleEntity::getId, role -> role));
        List<PlatformUserRoleGrantView> views = new ArrayList<>();
        for (PlatformUserRoleEntity grant : grants) {
            PlatformRoleEntity role = roles.get(grant.getRoleId());
            views.add(new PlatformUserRoleGrantView(
                    grant.getId(),
                    grant.getRoleId(),
                    role == null ? null : role.getRoleCode(),
                    role == null ? null : role.getRoleName(),
                    grant.getScopeType(),
                    grant.getScopeValue()));
        }
        return views;
    }

    private String requireText(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private PlatformAuthProviderView toAuthProviderView(PlatformAuthProviderEntity entity) {
        return new PlatformAuthProviderView(
                entity.getId(),
                entity.getProviderCode(),
                entity.getProviderName(),
                entity.getProviderType(),
                entity.getStatus(),
                hasNonEmptyProviderConfig(entity),
                instantText(entity.getCreatedAt()),
                instantText(entity.getUpdatedAt()));
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private boolean hasNonEmptyProviderConfig(PlatformAuthProviderEntity entity) {
        return StringUtils.hasText(entity.getConfigJson()) && !"{}".equals(entity.getConfigJson().trim());
    }

    private PlatformUserView toUserView(PlatformUserEntity entity) {
        return new PlatformUserView(
                entity.getId(),
                entity.getUsername(),
                entity.getDisplayName(),
                entity.getStatus(),
                entity.getSourceProvider(),
                instantText(entity.getLastLoginAt()));
    }

    private PlatformRoleView toRoleView(PlatformRoleEntity entity) {
        return new PlatformRoleView(
                entity.getId(),
                entity.getRoleCode(),
                entity.getRoleName(),
                entity.getStatus());
    }

    private String instantText(LocalDateTime value) {
        return value == null ? null : value.atZone(ZoneId.systemDefault()).toInstant().toString();
    }

    public record PlatformLoginRequest(String username, String password) {
    }

    private record IssuedPlatformSession(PlatformLoginSessionEntity session, String accessToken) {
    }

    public record PlatformLoginResult(
            long expiresIn,
            String expiresAt,
            String sessionId,
            PlatformUserProfile principal
    ) {
    }

    public record PlatformSessionView(
            String sessionId,
            String expiresAt,
            PlatformUserProfile principal
    ) {
    }

    public record PlatformUserProfile(
            Long userId,
            String username,
            String displayName,
            List<String> roles,
            List<String> permissions,
            List<PlatformPermissionGrant> permissionGrants
    ) {
    }

    public record PlatformAuthProviderView(
            Long id,
            String providerCode,
            String providerName,
            String providerType,
            String status,
            boolean configurationPresent,
            String createdAt,
            String updatedAt
    ) {
    }

    public record PlatformAuthProviderCommand(
            String providerCode,
            String providerName,
            String providerType,
            String status,
            String configJson
    ) {
    }

    public record PlatformUserView(
            Long id,
            String username,
            String displayName,
            String status,
            String sourceProvider,
            String lastLoginAt
    ) {
    }

    public record PlatformRoleView(
            Long id,
            String roleCode,
            String roleName,
            String status
    ) {
    }

    public record PlatformUserRoleGrantView(
            Long id,
            Long roleId,
            String roleCode,
            String roleName,
            String scopeType,
            String scopeValue
    ) {
    }

    public record PlatformUserRoleGrantCommand(
            Long roleId,
            String scopeType,
            String scopeValue
    ) {
    }

    private record NormalizedRoleGrantCommand(
            Long roleId,
            String scopeType,
            String scopeValue
    ) {
    }
}
