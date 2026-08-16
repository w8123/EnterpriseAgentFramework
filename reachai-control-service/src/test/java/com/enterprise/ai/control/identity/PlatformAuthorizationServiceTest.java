package com.enterprise.ai.control.identity;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlatformAuthorizationServiceTest {

    @Test
    void resolvesDatabasePermissionsOnlyForActiveRolesAndGlobalScope() {
        PlatformUserRoleMapper userRoleMapper = mock(PlatformUserRoleMapper.class);
        PlatformRoleMapper roleMapper = mock(PlatformRoleMapper.class);
        PlatformRolePermissionMapper rolePermissionMapper = mock(PlatformRolePermissionMapper.class);
        PlatformPermissionMapper permissionMapper = mock(PlatformPermissionMapper.class);
        PlatformAuthorizationService service = new PlatformAuthorizationService(
                userRoleMapper, roleMapper, rolePermissionMapper, permissionMapper);
        PlatformUserEntity user = user(7L);
        PlatformLoginSessionEntity loginSession = session("pls_7", 7L);
        PlatformRoleEntity admin = role(3L, "PLATFORM_ADMIN", "ACTIVE");
        PlatformRoleEntity disabled = role(4L, "AUDITOR", "INACTIVE");
        PlatformRolePermissionEntity binding = new PlatformRolePermissionEntity();
        binding.setRoleId(3L);
        binding.setPermissionId(9L);
        PlatformPermissionEntity permission = new PlatformPermissionEntity();
        permission.setId(9L);
        permission.setPermissionCode("platform:admin");

        when(userRoleMapper.selectList(any())).thenReturn(List.of(
                grant(7L, 3L, "GLOBAL", "*"),
                grant(7L, 4L, "GLOBAL", "*")));
        when(roleMapper.selectBatchIds(any())).thenReturn(List.of(admin, disabled));
        when(rolePermissionMapper.selectList(any())).thenReturn(List.of(binding));
        when(permissionMapper.selectBatchIds(any())).thenReturn(List.of(permission));

        PlatformAuthenticatedSession authenticated = service.authenticatedSession(user, loginSession);

        assertEquals(List.of("PLATFORM_ADMIN"), authenticated.roles());
        assertEquals(List.of("platform:admin"), authenticated.permissions());
        assertTrue(authenticated.hasGlobalPermission("platform:admin"));
    }

    @Test
    void projectScopedPermissionCannotElevateToPlatformAdministration() {
        PlatformUserRoleMapper userRoleMapper = mock(PlatformUserRoleMapper.class);
        PlatformRoleMapper roleMapper = mock(PlatformRoleMapper.class);
        PlatformRolePermissionMapper rolePermissionMapper = mock(PlatformRolePermissionMapper.class);
        PlatformPermissionMapper permissionMapper = mock(PlatformPermissionMapper.class);
        PlatformAuthorizationService service = new PlatformAuthorizationService(
                userRoleMapper, roleMapper, rolePermissionMapper, permissionMapper);
        PlatformRoleEntity admin = role(3L, "PLATFORM_ADMIN", "ACTIVE");
        PlatformRolePermissionEntity binding = new PlatformRolePermissionEntity();
        binding.setRoleId(3L);
        binding.setPermissionId(9L);
        PlatformPermissionEntity permission = new PlatformPermissionEntity();
        permission.setId(9L);
        permission.setPermissionCode("platform:admin");
        when(userRoleMapper.selectList(any())).thenReturn(List.of(grant(7L, 3L, "PROJECT", "demo")));
        when(roleMapper.selectBatchIds(any())).thenReturn(List.of(admin));
        when(rolePermissionMapper.selectList(any())).thenReturn(List.of(binding));
        when(permissionMapper.selectBatchIds(any())).thenReturn(List.of(permission));

        PlatformAuthenticatedSession authenticated = service.authenticatedSession(user(7L), session("pls_7", 7L));
        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.requireGlobalPermission(authenticated, "platform:admin"));

        assertFalse(authenticated.hasGlobalPermission("platform:admin"));
        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
    }

    private PlatformUserEntity user(Long id) {
        PlatformUserEntity user = new PlatformUserEntity();
        user.setId(id);
        user.setStatus("ACTIVE");
        return user;
    }

    private PlatformLoginSessionEntity session(String sessionId, Long userId) {
        PlatformLoginSessionEntity session = new PlatformLoginSessionEntity();
        session.setSessionId(sessionId);
        session.setUserId(userId);
        session.setExpiresAt(LocalDateTime.now().plusHours(1));
        return session;
    }

    private PlatformRoleEntity role(Long id, String code, String status) {
        PlatformRoleEntity role = new PlatformRoleEntity();
        role.setId(id);
        role.setRoleCode(code);
        role.setStatus(status);
        return role;
    }

    private PlatformUserRoleEntity grant(Long userId, Long roleId, String scopeType, String scopeValue) {
        PlatformUserRoleEntity grant = new PlatformUserRoleEntity();
        grant.setUserId(userId);
        grant.setRoleId(roleId);
        grant.setScopeType(scopeType);
        grant.setScopeValue(scopeValue);
        return grant;
    }
}
