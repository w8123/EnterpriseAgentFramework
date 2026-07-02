package com.enterprise.ai.control.identity;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequiredArgsConstructor
public class PlatformIdentityController {

    private static final long LOGIN_TTL_SECONDS = 86_400L;

    private final PlatformUserMapper userMapper;
    private final PlatformRoleMapper roleMapper;
    private final PlatformUserRoleMapper userRoleMapper;
    private final PlatformLoginSessionMapper sessionMapper;
    private final PlatformAuthProviderMapper authProviderMapper;

    @PostMapping("/api/platform/auth/login")
    public ResponseEntity<PlatformLoginResult> login(@RequestBody PlatformLoginRequest request) {
        String username = requireText(request == null ? null : request.username(), "username");
        String password = requireText(request == null ? null : request.password(), "password");
        PlatformUserEntity user = userMapper.selectOne(new LambdaQueryWrapper<PlatformUserEntity>()
                .eq(PlatformUserEntity::getUsername, username)
                .last("limit 1"));
        if (user == null) {
            user = createLocalUser(username, password);
        }
        if (!"ACTIVE".equalsIgnoreCase(user.getStatus()) || !passwordMatches(user, password)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        user.setLastLoginAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(user);
        PlatformLoginSessionEntity session = createSession(user);
        sessionMapper.insert(session);
        return ResponseEntity.ok(new PlatformLoginResult(
                session.getAccessTokenId(),
                LOGIN_TTL_SECONDS,
                instantText(session.getExpiresAt()),
                toProfile(user)));
    }

    @GetMapping("/api/platform/auth/me")
    public ResponseEntity<PlatformUserProfile> me(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        PlatformUserEntity user = resolveBearerUser(authorization);
        return user == null
                ? ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
                : ResponseEntity.ok(toProfile(user));
    }

    @PostMapping("/api/platform/auth/logout")
    public ResponseEntity<Void> logout(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        String token = bearerToken(authorization);
        if (StringUtils.hasText(token)) {
            sessionMapper.update(null, new LambdaUpdateWrapper<PlatformLoginSessionEntity>()
                    .eq(PlatformLoginSessionEntity::getAccessTokenId, token)
                    .isNull(PlatformLoginSessionEntity::getRevokedAt)
                    .set(PlatformLoginSessionEntity::getRevokedAt, LocalDateTime.now()));
        }
        return ResponseEntity.ok().build();
    }

    @GetMapping("/api/platform/auth-providers")
    public ResponseEntity<List<PlatformAuthProviderView>> listAuthProviders() {
        return ResponseEntity.ok(authProviderMapper.selectList(new LambdaQueryWrapper<PlatformAuthProviderEntity>()
                        .orderByAsc(PlatformAuthProviderEntity::getId))
                .stream()
                .map(this::toAuthProviderView)
                .toList());
    }

    @PostMapping("/api/platform/auth-providers")
    public ResponseEntity<PlatformAuthProviderView> saveAuthProvider(@RequestBody PlatformAuthProviderCommand request) {
        String providerCode = requireText(request == null ? null : request.providerCode(), "providerCode");
        PlatformAuthProviderEntity entity = authProviderMapper.selectOne(new LambdaQueryWrapper<PlatformAuthProviderEntity>()
                .eq(PlatformAuthProviderEntity::getProviderCode, providerCode)
                .last("limit 1"));
        if (entity == null) {
            entity = new PlatformAuthProviderEntity();
            entity.setProviderCode(providerCode);
            entity.setCreatedAt(LocalDateTime.now());
        }
        entity.setProviderName(requireText(request.providerName(), "providerName"));
        entity.setProviderType(requireText(request.providerType(), "providerType"));
        entity.setStatus(StringUtils.hasText(request.status()) ? request.status().trim() : "ACTIVE");
        entity.setConfigJson(StringUtils.hasText(request.configJson()) ? request.configJson().trim() : "{}");
        entity.setUpdatedAt(LocalDateTime.now());
        if (entity.getId() == null) {
            authProviderMapper.insert(entity);
        } else {
            authProviderMapper.updateById(entity);
        }
        return ResponseEntity.ok(toAuthProviderView(entity));
    }

    @GetMapping("/api/platform/users")
    public ResponseEntity<List<PlatformUserView>> listUsers() {
        return ResponseEntity.ok(userMapper.selectList(new LambdaQueryWrapper<PlatformUserEntity>()
                        .orderByDesc(PlatformUserEntity::getId))
                .stream()
                .map(this::toUserView)
                .toList());
    }

    @GetMapping("/api/platform/roles")
    public ResponseEntity<List<PlatformRoleView>> listRoles() {
        return ResponseEntity.ok(roleMapper.selectList(new LambdaQueryWrapper<PlatformRoleEntity>()
                        .orderByAsc(PlatformRoleEntity::getId))
                .stream()
                .map(this::toRoleView)
                .toList());
    }

    @GetMapping("/api/platform/users/{userId}/roles")
    public ResponseEntity<List<PlatformUserRoleGrantView>> listUserRoleGrants(@PathVariable Long userId) {
        return ResponseEntity.ok(roleGrantViews(userId));
    }

    @PutMapping("/api/platform/users/{userId}/roles")
    public ResponseEntity<List<PlatformUserRoleGrantView>> saveUserRoleGrants(
            @PathVariable Long userId,
            @RequestBody List<PlatformUserRoleGrantCommand> commands) {
        userRoleMapper.delete(new LambdaQueryWrapper<PlatformUserRoleEntity>()
                .eq(PlatformUserRoleEntity::getUserId, userId));
        for (PlatformUserRoleGrantCommand command : commands == null ? List.<PlatformUserRoleGrantCommand>of() : commands) {
            PlatformUserRoleEntity entity = new PlatformUserRoleEntity();
            entity.setUserId(userId);
            entity.setRoleId(command.roleId());
            entity.setScopeType(StringUtils.hasText(command.scopeType()) ? command.scopeType().trim() : "GLOBAL");
            entity.setScopeValue(StringUtils.hasText(command.scopeValue()) ? command.scopeValue().trim() : "*");
            entity.setCreatedAt(LocalDateTime.now());
            userRoleMapper.insert(entity);
        }
        return ResponseEntity.ok(roleGrantViews(userId));
    }

    private PlatformUserEntity createLocalUser(String username, String password) {
        PlatformUserEntity entity = new PlatformUserEntity();
        entity.setUsername(username);
        entity.setDisplayName(username);
        entity.setStatus("ACTIVE");
        entity.setSourceProvider("LOCAL");
        entity.setExternalSubject(username);
        entity.setPasswordHash("{plain}" + password);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        userMapper.insert(entity);
        grantDefaultAdminRole(entity.getId());
        return entity;
    }

    private void grantDefaultAdminRole(Long userId) {
        if (userId == null) {
            return;
        }
        PlatformRoleEntity adminRole = roleMapper.selectOne(new LambdaQueryWrapper<PlatformRoleEntity>()
                .eq(PlatformRoleEntity::getRoleCode, "PLATFORM_ADMIN")
                .last("limit 1"));
        if (adminRole == null || adminRole.getId() == null) {
            return;
        }
        PlatformUserRoleEntity grant = new PlatformUserRoleEntity();
        grant.setUserId(userId);
        grant.setRoleId(adminRole.getId());
        grant.setScopeType("GLOBAL");
        grant.setScopeValue("*");
        grant.setCreatedAt(LocalDateTime.now());
        userRoleMapper.insert(grant);
    }

    private PlatformLoginSessionEntity createSession(PlatformUserEntity user) {
        PlatformLoginSessionEntity entity = new PlatformLoginSessionEntity();
        entity.setSessionId("pls_" + UUID.randomUUID());
        entity.setUserId(user.getId());
        entity.setProvider(user.getSourceProvider());
        entity.setAccessTokenId("pat_" + UUID.randomUUID());
        entity.setExpiresAt(LocalDateTime.now().plusSeconds(LOGIN_TTL_SECONDS));
        entity.setCreatedAt(LocalDateTime.now());
        return entity;
    }

    private PlatformUserEntity resolveBearerUser(String authorization) {
        String token = bearerToken(authorization);
        if (!StringUtils.hasText(token)) {
            return null;
        }
        PlatformLoginSessionEntity session = sessionMapper.selectOne(new LambdaQueryWrapper<PlatformLoginSessionEntity>()
                .eq(PlatformLoginSessionEntity::getAccessTokenId, token)
                .isNull(PlatformLoginSessionEntity::getRevokedAt)
                .last("limit 1"));
        if (session == null || session.getExpiresAt() == null || session.getExpiresAt().isBefore(LocalDateTime.now())) {
            return null;
        }
        PlatformUserEntity user = userMapper.selectById(session.getUserId());
        return user == null || !"ACTIVE".equalsIgnoreCase(user.getStatus()) ? null : user;
    }

    private String bearerToken(String authorization) {
        if (!StringUtils.hasText(authorization)) {
            return null;
        }
        String trimmed = authorization.trim();
        return trimmed.regionMatches(true, 0, "Bearer ", 0, 7) ? trimmed.substring(7).trim() : null;
    }

    private boolean passwordMatches(PlatformUserEntity user, String password) {
        if (!StringUtils.hasText(user.getPasswordHash())) {
            return true;
        }
        if (user.getPasswordHash().startsWith("{plain}")) {
            return user.getPasswordHash().substring("{plain}".length()).equals(password);
        }
        return user.getPasswordHash().equals(password);
    }

    private PlatformUserProfile toProfile(PlatformUserEntity user) {
        List<String> roles = roleCodes(user.getId());
        return new PlatformUserProfile(
                user.getId(),
                user.getUsername(),
                user.getDisplayName(),
                roles,
                permissions(roles));
    }

    private List<String> roleCodes(Long userId) {
        return roleGrantViews(userId).stream()
                .map(PlatformUserRoleGrantView::roleCode)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
    }

    private List<String> permissions(List<String> roleCodes) {
        if (roleCodes.stream().anyMatch("PLATFORM_ADMIN"::equalsIgnoreCase)) {
            return List.of("*", "platform:read", "platform:write", "platform:admin");
        }
        return roleCodes.isEmpty() ? List.of() : List.of("platform:read", "platform:write");
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
                entity.getConfigJson(),
                instantText(entity.getCreatedAt()),
                instantText(entity.getUpdatedAt()));
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

    public record PlatformLoginResult(
            String accessToken,
            long expiresIn,
            String expiresAt,
            PlatformUserProfile principal
    ) {
    }

    public record PlatformUserProfile(
            Long userId,
            String username,
            String displayName,
            List<String> roles,
            List<String> permissions
    ) {
    }

    public record PlatformAuthProviderView(
            Long id,
            String providerCode,
            String providerName,
            String providerType,
            String status,
            String configJson,
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
}
