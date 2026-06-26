package com.enterprise.ai.agent.platform.control.auth;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.LinkedHashMap;

@Service
@RequiredArgsConstructor
public class PlatformAuthService {

    private static final List<String> DEFAULT_PERMISSIONS = List.of(
            "*", "platform:read", "platform:write", "platform:admin",
            "context:runtime-user:review", "context:runtime-user:mapping:manage");

    private static final Map<String, List<String>> DEFAULT_ROLE_PERMISSIONS = Map.of(
            "PLATFORM_ADMIN", List.of("*", "platform:read", "platform:write", "platform:admin",
                    "context:runtime-user:review", "context:runtime-user:mapping:manage"),
            "AGENT_DESIGNER", List.of("platform:read", "platform:write"),
            "PROJECT_OWNER", List.of("platform:read", "platform:write"),
            "OPERATOR", List.of("platform:read", "platform:write"),
            "AUDITOR", List.of("platform:read"));

    private final PlatformAuthProperties properties;
    private final PlatformPasswordHasher passwordHasher;
    private final List<PlatformAuthProvider> providers;
    private final PlatformUserMapper userMapper;
    private final PlatformRoleMapper roleMapper;
    private final PlatformUserRoleMapper userRoleMapper;
    private final PlatformPermissionMapper permissionMapper;
    private final PlatformRolePermissionMapper rolePermissionMapper;
    private final PlatformLoginSessionMapper sessionMapper;
    private final PlatformAuthProviderConfigService providerConfigService;

    @PostConstruct
    @Transactional
    public void bootstrapLocalAdmin() {
        LocalDateTime now = LocalDateTime.now();
        bootstrapDefaultRoles(now);
        if (!properties.getLocal().isEnabled()) {
            return;
        }
        PlatformRoleEntity adminRole = ensureRole("PLATFORM_ADMIN", "平台管理员", "平台全量管理与配置", now);

        String username = properties.getLocal().getBootstrapAdmin().getUsername();
        PlatformUserEntity admin = userMapper.selectOne(Wrappers.<PlatformUserEntity>lambdaQuery()
                .eq(PlatformUserEntity::getUsername, username)
                .last("LIMIT 1"));
        if (admin == null) {
            admin = new PlatformUserEntity();
            admin.setUsername(username);
            admin.setDisplayName(properties.getLocal().getBootstrapAdmin().getDisplayName());
            admin.setStatus("ACTIVE");
            admin.setSourceProvider("LOCAL");
            admin.setExternalSubject("local:" + username);
            admin.setPasswordHash(passwordHasher.hash(properties.getLocal().getBootstrapAdmin().getPassword()));
            admin.setCreatedAt(now);
            admin.setUpdatedAt(now);
            userMapper.insert(admin);
        }
        boolean bound = userRoleMapper.selectCount(Wrappers.<PlatformUserRoleEntity>lambdaQuery()
                .eq(PlatformUserRoleEntity::getUserId, admin.getId())
                .eq(PlatformUserRoleEntity::getRoleId, adminRole.getId())) > 0;
        if (!bound) {
            PlatformUserRoleEntity row = new PlatformUserRoleEntity();
            row.setUserId(admin.getId());
            row.setRoleId(adminRole.getId());
            row.setScopeType("GLOBAL");
            row.setScopeValue("*");
            row.setCreatedAt(now);
            userRoleMapper.insert(row);
        }
    }

    @Transactional
    public PlatformLoginResult login(String username, String password, String ip, String userAgent) {
        return login(PlatformLoginRequest.builder()
                .username(username)
                .password(password)
                .ip(ip)
                .userAgent(userAgent)
                .build());
    }

    @Transactional
    public PlatformLoginResult login(PlatformLoginRequest request) {
        PlatformLoginRequest resolvedRequest = resolveProviderConfig(request);
        PlatformAuthProvider provider = resolveProvider(firstNonBlank(resolvedRequest.getProviderType(), properties.getProvider()));
        PlatformUserProfile profile = provider.authenticate(resolvedRequest);
        PlatformUserEntity user = upsertPlatformUser(profile);
        bindProfileRoles(user.getId(), profile.roleCodes());
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiresAt = now.plusSeconds(properties.getSession().getTtlSeconds());
        String token = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        PlatformLoginSessionEntity session = new PlatformLoginSessionEntity();
        session.setSessionId(UUID.randomUUID().toString());
        session.setUserId(user.getId());
        session.setProvider(profile.sourceProvider());
        session.setAccessTokenId(token);
        session.setIp(resolvedRequest.getIp());
        session.setUserAgent(resolvedRequest.getUserAgent());
        session.setExpiresAt(expiresAt);
        session.setCreatedAt(now);
        sessionMapper.insert(session);

        user.setLastLoginAt(now);
        user.setUpdatedAt(now);
        userMapper.updateById(user);
        return new PlatformLoginResult(
                token,
                Duration.between(now, expiresAt).toSeconds(),
                expiresAt,
                loadPrincipal(user));
    }

    public Optional<PlatformPrincipal> authenticate(String bearerToken) {
        if (!StringUtils.hasText(bearerToken)) {
            return Optional.empty();
        }
        PlatformLoginSessionEntity session = sessionMapper.selectOne(Wrappers.<PlatformLoginSessionEntity>lambdaQuery()
                .eq(PlatformLoginSessionEntity::getAccessTokenId, bearerToken)
                .isNull(PlatformLoginSessionEntity::getRevokedAt)
                .last("LIMIT 1"));
        if (session == null || session.getExpiresAt() == null || session.getExpiresAt().isBefore(LocalDateTime.now())) {
            return Optional.empty();
        }
        PlatformUserEntity user = userMapper.selectById(session.getUserId());
        if (user == null || !"ACTIVE".equalsIgnoreCase(user.getStatus())) {
            return Optional.empty();
        }
        return Optional.of(loadPrincipal(user));
    }

    @Transactional
    public void logout(String bearerToken) {
        if (!StringUtils.hasText(bearerToken)) {
            return;
        }
        PlatformLoginSessionEntity session = sessionMapper.selectOne(Wrappers.<PlatformLoginSessionEntity>lambdaQuery()
                .eq(PlatformLoginSessionEntity::getAccessTokenId, bearerToken)
                .isNull(PlatformLoginSessionEntity::getRevokedAt)
                .last("LIMIT 1"));
        if (session != null) {
            session.setRevokedAt(LocalDateTime.now());
            sessionMapper.updateById(session);
        }
    }

    public PlatformPrincipal loadPrincipal(PlatformUserEntity user) {
        List<PlatformUserRoleEntity> userRoles = userRoleMapper.selectList(Wrappers.<PlatformUserRoleEntity>lambdaQuery()
                .eq(PlatformUserRoleEntity::getUserId, user.getId()));
        Set<Long> roleIds = userRoles.stream().map(PlatformUserRoleEntity::getRoleId).collect(Collectors.toSet());
        if (roleIds.isEmpty()) {
            return new PlatformPrincipal(user.getId(), user.getUsername(), user.getDisplayName(), Set.of(), Set.of());
        }
        List<PlatformRoleEntity> roles = roleMapper.selectList(Wrappers.<PlatformRoleEntity>lambdaQuery()
                .in(PlatformRoleEntity::getId, roleIds)
                .eq(PlatformRoleEntity::getStatus, "ACTIVE"));
        Set<String> roleCodes = roles.stream().map(PlatformRoleEntity::getRoleCode).collect(Collectors.toSet());
        Set<Long> activeRoleIds = roles.stream().map(PlatformRoleEntity::getId).collect(Collectors.toSet());
        Map<Long, String> roleCodeById = roles.stream()
                .collect(Collectors.toMap(PlatformRoleEntity::getId, PlatformRoleEntity::getRoleCode));
        Set<PlatformPrincipal.RoleGrant> roleGrants = userRoles.stream()
                .filter(row -> activeRoleIds.contains(row.getRoleId()))
                .map(row -> new PlatformPrincipal.RoleGrant(
                        roleCodeById.get(row.getRoleId()),
                        row.getScopeType(),
                        row.getScopeValue()))
                .collect(Collectors.toSet());
        if (activeRoleIds.isEmpty()) {
            return new PlatformPrincipal(user.getId(), user.getUsername(), user.getDisplayName(), roleCodes, Set.of());
        }
        List<PlatformRolePermissionEntity> rolePermissions = rolePermissionMapper.selectList(
                Wrappers.<PlatformRolePermissionEntity>lambdaQuery()
                        .in(PlatformRolePermissionEntity::getRoleId, activeRoleIds));
        Set<Long> permissionIds = rolePermissions.stream()
                .map(PlatformRolePermissionEntity::getPermissionId)
                .collect(Collectors.toSet());
        Set<String> permissions = permissionIds.isEmpty() ? Set.of() : permissionMapper.selectList(
                        Wrappers.<PlatformPermissionEntity>lambdaQuery().in(PlatformPermissionEntity::getId, permissionIds))
                .stream()
                .map(PlatformPermissionEntity::getPermissionCode)
                .collect(Collectors.toSet());
        return new PlatformPrincipal(user.getId(), user.getUsername(), user.getDisplayName(), roleCodes, permissions, roleGrants);
    }

    public List<PlatformUserEntity> listUsers() {
        return userMapper.selectList(Wrappers.<PlatformUserEntity>lambdaQuery().orderByDesc(PlatformUserEntity::getCreatedAt));
    }

    public List<PlatformRoleEntity> listRoles() {
        return roleMapper.selectList(Wrappers.<PlatformRoleEntity>lambdaQuery().orderByAsc(PlatformRoleEntity::getRoleCode));
    }

    public List<UserRoleGrantView> listUserRoleGrants(Long userId) {
        List<PlatformUserRoleEntity> userRoles = userRoleMapper.selectList(Wrappers.<PlatformUserRoleEntity>lambdaQuery()
                .eq(PlatformUserRoleEntity::getUserId, userId));
        Set<Long> roleIds = userRoles.stream()
                .map(PlatformUserRoleEntity::getRoleId)
                .collect(Collectors.toSet());
        Map<Long, PlatformRoleEntity> roleById = roleIds.isEmpty() ? Map.of() : roleMapper.selectList(
                        Wrappers.<PlatformRoleEntity>lambdaQuery().in(PlatformRoleEntity::getId, roleIds))
                .stream()
                .collect(Collectors.toMap(PlatformRoleEntity::getId, Function.identity()));
        return userRoles.stream()
                .map(row -> {
                    PlatformRoleEntity role = roleById.get(row.getRoleId());
                    return new UserRoleGrantView(
                            row.getId(),
                            row.getRoleId(),
                            role == null ? null : role.getRoleCode(),
                            role == null ? null : role.getRoleName(),
                            row.getScopeType(),
                            row.getScopeValue());
                })
                .toList();
    }

    @Transactional
    public List<UserRoleGrantView> replaceUserRoleGrants(Long userId, List<UserRoleGrantCommand> grants) {
        PlatformUserEntity user = userMapper.selectById(userId);
        if (user == null) {
            throw new IllegalArgumentException("platform user does not exist: " + userId);
        }
        List<UserRoleGrantCommand> normalized = normalizeRoleGrants(grants);
        Set<Long> roleIds = normalized.stream()
                .map(UserRoleGrantCommand::roleId)
                .collect(Collectors.toSet());
        Map<Long, PlatformRoleEntity> activeRoles = roleIds.isEmpty() ? Map.of() : roleMapper.selectList(
                        Wrappers.<PlatformRoleEntity>lambdaQuery()
                                .in(PlatformRoleEntity::getId, roleIds)
                                .eq(PlatformRoleEntity::getStatus, "ACTIVE"))
                .stream()
                .collect(Collectors.toMap(PlatformRoleEntity::getId, Function.identity()));
        if (activeRoles.size() != roleIds.size()) {
            throw new IllegalArgumentException("one or more platform roles are missing or inactive");
        }
        userRoleMapper.delete(Wrappers.<PlatformUserRoleEntity>lambdaQuery()
                .eq(PlatformUserRoleEntity::getUserId, userId));
        LocalDateTime now = LocalDateTime.now();
        for (UserRoleGrantCommand grant : normalized) {
            PlatformUserRoleEntity row = new PlatformUserRoleEntity();
            row.setUserId(userId);
            row.setRoleId(grant.roleId());
            row.setScopeType(grant.scopeType());
            row.setScopeValue(grant.scopeValue());
            row.setCreatedAt(now);
            userRoleMapper.insert(row);
        }
        return listUserRoleGrants(userId);
    }

    private List<UserRoleGrantCommand> normalizeRoleGrants(List<UserRoleGrantCommand> grants) {
        if (grants == null || grants.isEmpty()) {
            return List.of();
        }
        Map<String, UserRoleGrantCommand> unique = new LinkedHashMap<>();
        for (UserRoleGrantCommand grant : grants) {
            if (grant == null || grant.roleId() == null) {
                throw new IllegalArgumentException("roleId is required");
            }
            String scopeType = StringUtils.hasText(grant.scopeType())
                    ? grant.scopeType().trim().toUpperCase(Locale.ROOT)
                    : "GLOBAL";
            String scopeValue = StringUtils.hasText(grant.scopeValue()) ? grant.scopeValue().trim() : "*";
            UserRoleGrantCommand normalized = new UserRoleGrantCommand(grant.roleId(), scopeType, scopeValue);
            unique.put(grant.roleId() + "|" + scopeType + "|" + scopeValue, normalized);
        }
        return List.copyOf(unique.values());
    }

    private PlatformAuthProvider resolveProvider(String providerType) {
        String expected = (StringUtils.hasText(providerType) ? providerType : "LOCAL").toUpperCase(Locale.ROOT);
        return providers.stream()
                .filter(provider -> expected.equalsIgnoreCase(provider.providerType()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("platform auth provider is not registered: " + expected));
    }

    private PlatformLoginRequest resolveProviderConfig(PlatformLoginRequest request) {
        if (request == null || !StringUtils.hasText(request.getProviderCode())) {
            return request;
        }
        PlatformAuthProviderConfigService.RuntimeProviderConfig config =
                providerConfigService.loadActiveProvider(request.getProviderCode());
        return request.toBuilder()
                .providerCode(config.providerCode())
                .providerType(config.providerType())
                .providerConfig(config.config())
                .build();
    }

    private String firstNonBlank(String preferred, String fallback) {
        return StringUtils.hasText(preferred) ? preferred : fallback;
    }

    private PlatformUserEntity upsertPlatformUser(PlatformUserProfile profile) {
        LocalDateTime now = LocalDateTime.now();
        PlatformUserEntity user = userMapper.selectOne(Wrappers.<PlatformUserEntity>lambdaQuery()
                .eq(PlatformUserEntity::getSourceProvider, profile.sourceProvider())
                .eq(PlatformUserEntity::getExternalSubject, profile.externalSubject())
                .last("LIMIT 1"));
        if (user == null) {
            user = new PlatformUserEntity();
            user.setSourceProvider(profile.sourceProvider());
            user.setExternalSubject(profile.externalSubject());
            user.setStatus("ACTIVE");
            user.setCreatedAt(now);
        }
        user.setUsername(profile.username());
        user.setDisplayName(StringUtils.hasText(profile.displayName()) ? profile.displayName() : profile.username());
        user.setEmail(profile.email());
        user.setMobile(profile.mobile());
        user.setUpdatedAt(now);
        if (user.getId() == null) {
            userMapper.insert(user);
        } else {
            userMapper.updateById(user);
        }
        return user;
    }

    private void bindProfileRoles(Long userId, Set<String> roleCodes) {
        if (userId == null || roleCodes == null || roleCodes.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        for (String roleCode : roleCodes) {
            if (!StringUtils.hasText(roleCode)) {
                continue;
            }
            PlatformRoleEntity role = ensureRole(roleCode.trim(), roleCode.trim(), "External provider role", now);
            boolean exists = userRoleMapper.selectCount(Wrappers.<PlatformUserRoleEntity>lambdaQuery()
                    .eq(PlatformUserRoleEntity::getUserId, userId)
                    .eq(PlatformUserRoleEntity::getRoleId, role.getId())) > 0;
            if (!exists) {
                PlatformUserRoleEntity row = new PlatformUserRoleEntity();
                row.setUserId(userId);
                row.setRoleId(role.getId());
                row.setScopeType("GLOBAL");
                row.setScopeValue("*");
                row.setCreatedAt(now);
                userRoleMapper.insert(row);
            }
        }
    }

    private void bootstrapDefaultRoles(LocalDateTime now) {
        Map<String, PlatformPermissionEntity> permissions = DEFAULT_PERMISSIONS.stream()
                .map(this::ensurePermission)
                .collect(Collectors.toMap(PlatformPermissionEntity::getPermissionCode, Function.identity()));
        ensureRoleWithPermissions("PLATFORM_ADMIN", "平台管理员", "平台全量管理与配置", permissions, now);
        ensureRoleWithPermissions("AGENT_DESIGNER", "智能体设计者", "创建与编辑智能体及工作流", permissions, now);
        ensureRoleWithPermissions("PROJECT_OWNER", "项目负责人", "管理已分配业务项目", permissions, now);
        ensureRoleWithPermissions("OPERATOR", "运维操作员", "运行、调试与回放会话", permissions, now);
        ensureRoleWithPermissions("AUDITOR", "审计员", "只读审计与追踪", permissions, now);
    }

    private PlatformRoleEntity ensureRoleWithPermissions(String code,
                                                         String name,
                                                         String description,
                                                         Map<String, PlatformPermissionEntity> permissions,
                                                         LocalDateTime now) {
        PlatformRoleEntity role = ensureRole(code, name, description, now);
        for (String permissionCode : DEFAULT_ROLE_PERMISSIONS.getOrDefault(code, List.of())) {
            PlatformPermissionEntity permission = permissions.get(permissionCode);
            if (permission == null) {
                continue;
            }
            boolean exists = rolePermissionMapper.selectCount(Wrappers.<PlatformRolePermissionEntity>lambdaQuery()
                    .eq(PlatformRolePermissionEntity::getRoleId, role.getId())
                    .eq(PlatformRolePermissionEntity::getPermissionId, permission.getId())) > 0;
            if (!exists) {
                PlatformRolePermissionEntity row = new PlatformRolePermissionEntity();
                row.setRoleId(role.getId());
                row.setPermissionId(permission.getId());
                rolePermissionMapper.insert(row);
            }
        }
        return role;
    }

    private PlatformRoleEntity ensureRole(String code, String name, String description, LocalDateTime now) {
        PlatformRoleEntity role = roleMapper.selectOne(Wrappers.<PlatformRoleEntity>lambdaQuery()
                .eq(PlatformRoleEntity::getRoleCode, code)
                .last("LIMIT 1"));
        if (role == null) {
            role = new PlatformRoleEntity();
            role.setRoleCode(code);
            role.setRoleName(name);
            role.setDescription(description);
            role.setStatus("ACTIVE");
            role.setCreatedAt(now);
            role.setUpdatedAt(now);
            roleMapper.insert(role);
        }
        return role;
    }

    private PlatformPermissionEntity ensurePermission(String code) {
        PlatformPermissionEntity permission = permissionMapper.selectOne(Wrappers.<PlatformPermissionEntity>lambdaQuery()
                .eq(PlatformPermissionEntity::getPermissionCode, code)
                .last("LIMIT 1"));
        if (permission == null) {
            permission = new PlatformPermissionEntity();
            permission.setPermissionCode(code);
            permission.setPermissionName(code);
            permission.setResourceType("PLATFORM");
            permission.setAction(code);
            permissionMapper.insert(permission);
        }
        return permission;
    }

    public record UserRoleGrantView(Long id, Long roleId, String roleCode, String roleName,
                                    String scopeType, String scopeValue) {
    }

    public record UserRoleGrantCommand(Long roleId, String scopeType, String scopeValue) {
    }
}
