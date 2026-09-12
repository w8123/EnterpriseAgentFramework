package com.enterprise.ai.control.identity;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Resolves database-backed platform grants and enforces server-side permissions. */
@Service
@RequiredArgsConstructor
public class PlatformAuthorizationService {

    private final PlatformUserRoleMapper userRoleMapper;
    private final PlatformRoleMapper roleMapper;
    private final PlatformRolePermissionMapper rolePermissionMapper;
    private final PlatformPermissionMapper permissionMapper;

    public PlatformAuthenticatedSession authenticatedSession(
            PlatformUserEntity user,
            PlatformLoginSessionEntity loginSession) {
        List<ResolvedRoleGrant> grants = resolveRoleGrants(user.getId());
        List<String> roles = grants.stream()
                .map(grant -> grant.role().getRoleCode())
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
        List<PlatformPermissionGrant> permissionGrants = resolvePermissionGrants(grants);
        List<String> permissions = permissionGrants.stream()
                .map(PlatformPermissionGrant::permissionCode)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
        return new PlatformAuthenticatedSession(
                PlatformPrincipal.fromUser(user),
                loginSession.getSessionId(),
                loginSession.getExpiresAt(),
                roles,
                permissions,
                permissionGrants);
    }

    public void requireGlobalPermission(PlatformAuthenticatedSession session, String permission) {
        if (session == null || !session.hasGlobalPermission(permission)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "platform permission is required: " + permission);
        }
    }

    /**
     * Admits a caller that owns the permission in at least one usable scope.
     * Domain code must still perform its resource-scope check before reading or
     * mutating a concrete resource.
     */
    public void requirePermission(PlatformAuthenticatedSession session, String permission) {
        if (session == null
                || session.permissions() == null
                || (!session.permissions().contains(permission)
                && !session.permissions().contains(PlatformPermissions.ALL))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "platform permission is required: " + permission);
        }
    }

    public void requireResourcePermission(PlatformAuthenticatedSession session,
                                          String permission,
                                          String resourceScope,
                                          String workspaceId,
                                          String projectCode) {
        if (session == null || !session.hasResourcePermission(
                permission, resourceScope, workspaceId, projectCode)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "platform permission is required for the scoped resource: " + permission);
        }
    }

    public List<ResolvedRoleGrant> resolveRoleGrants(Long userId) {
        if (userId == null) {
            return List.of();
        }
        List<PlatformUserRoleEntity> userRoles = userRoleMapper.selectList(
                new LambdaQueryWrapper<PlatformUserRoleEntity>()
                        .eq(PlatformUserRoleEntity::getUserId, userId));
        if (userRoles.isEmpty()) {
            return List.of();
        }
        List<Long> roleIds = userRoles.stream()
                .map(PlatformUserRoleEntity::getRoleId)
                .filter(id -> id != null)
                .distinct()
                .toList();
        if (roleIds.isEmpty()) {
            return List.of();
        }
        Map<Long, PlatformRoleEntity> activeRoles = roleMapper.selectBatchIds(roleIds).stream()
                .filter(role -> role.getId() != null && "ACTIVE".equalsIgnoreCase(role.getStatus()))
                .collect(java.util.stream.Collectors.toMap(PlatformRoleEntity::getId, role -> role));
        List<ResolvedRoleGrant> result = new ArrayList<>();
        for (PlatformUserRoleEntity userRole : userRoles) {
            PlatformRoleEntity role = activeRoles.get(userRole.getRoleId());
            if (role == null) {
                continue;
            }
            String scopeType = normalizeScopeType(userRole.getScopeType());
            String scopeValue = normalizeScopeValue(userRole.getScopeValue());
            if (!StringUtils.hasText(scopeType) || !StringUtils.hasText(scopeValue)) {
                continue;
            }
            result.add(new ResolvedRoleGrant(role, scopeType, scopeValue));
        }
        return List.copyOf(result);
    }

    private List<PlatformPermissionGrant> resolvePermissionGrants(List<ResolvedRoleGrant> grants) {
        if (grants.isEmpty()) {
            return List.of();
        }
        Set<Long> roleIds = grants.stream()
                .map(grant -> grant.role().getId())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<PlatformRolePermissionEntity> bindings = rolePermissionMapper.selectList(
                new LambdaQueryWrapper<PlatformRolePermissionEntity>()
                        .in(PlatformRolePermissionEntity::getRoleId, roleIds));
        if (bindings.isEmpty()) {
            return List.of();
        }
        Set<Long> permissionIds = bindings.stream()
                .map(PlatformRolePermissionEntity::getPermissionId)
                .filter(id -> id != null)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (permissionIds.isEmpty()) {
            return List.of();
        }
        Map<Long, String> permissionCodes = permissionMapper.selectBatchIds(permissionIds).stream()
                .filter(permission -> permission.getId() != null && StringUtils.hasText(permission.getPermissionCode()))
                .collect(java.util.stream.Collectors.toMap(
                        PlatformPermissionEntity::getId,
                        PlatformPermissionEntity::getPermissionCode));
        Map<Long, List<String>> permissionsByRole = new LinkedHashMap<>();
        for (PlatformRolePermissionEntity binding : bindings) {
            String permission = permissionCodes.get(binding.getPermissionId());
            if (permission != null) {
                permissionsByRole.computeIfAbsent(binding.getRoleId(), ignored -> new ArrayList<>()).add(permission);
            }
        }
        List<PlatformPermissionGrant> result = new ArrayList<>();
        for (ResolvedRoleGrant grant : grants) {
            for (String permission : permissionsByRole.getOrDefault(grant.role().getId(), List.of())) {
                result.add(new PlatformPermissionGrant(permission, grant.scopeType(), grant.scopeValue()));
            }
        }
        return List.copyOf(result);
    }

    private String normalizeScopeType(String value) {
        return StringUtils.hasText(value) ? value.trim().toUpperCase(java.util.Locale.ROOT) : "GLOBAL";
    }

    private String normalizeScopeValue(String value) {
        return StringUtils.hasText(value) ? value.trim() : "*";
    }

    public record ResolvedRoleGrant(
            PlatformRoleEntity role,
            String scopeType,
            String scopeValue
    ) {
    }
}
