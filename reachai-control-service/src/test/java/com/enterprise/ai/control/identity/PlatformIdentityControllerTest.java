package com.enterprise.ai.control.identity;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlatformIdentityControllerTest {

    @Test
    void localLoginCreatesSessionAndReturnsPrincipal() {
        PlatformUserMapper userMapper = mock(PlatformUserMapper.class);
        PlatformRoleMapper roleMapper = mock(PlatformRoleMapper.class);
        PlatformUserRoleMapper userRoleMapper = mock(PlatformUserRoleMapper.class);
        PlatformLoginSessionMapper sessionMapper = mock(PlatformLoginSessionMapper.class);
        PlatformIdentityController controller = controller(userMapper, roleMapper, userRoleMapper, sessionMapper);
        PlatformUserEntity user = user(7L, "jsh", "jsh");
        user.setPasswordHash("{plain}secret");
        when(userMapper.selectOne(any())).thenReturn(user);
        PlatformRoleEntity role = role(3L, "PLATFORM_ADMIN", "平台管理员");
        when(userRoleMapper.selectList(any())).thenReturn(List.of(userRole(7L, 3L)));
        when(roleMapper.selectBatchIds(List.of(3L))).thenReturn(List.of(role));

        ResponseEntity<PlatformIdentityController.PlatformLoginResult> response =
                controller.login(new PlatformIdentityController.PlatformLoginRequest("jsh", "secret"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertNotNull(response.getBody().accessToken());
        assertEquals(7L, response.getBody().principal().userId());
        assertEquals(List.of("PLATFORM_ADMIN"), response.getBody().principal().roles());
        verify(sessionMapper).insert(any());
        verify(userMapper).updateById(any());
    }

    @Test
    void currentUserResolvesBearerTokenFromLoginSession() {
        PlatformUserMapper userMapper = mock(PlatformUserMapper.class);
        PlatformRoleMapper roleMapper = mock(PlatformRoleMapper.class);
        PlatformUserRoleMapper userRoleMapper = mock(PlatformUserRoleMapper.class);
        PlatformLoginSessionMapper sessionMapper = mock(PlatformLoginSessionMapper.class);
        PlatformIdentityController controller = controller(userMapper, roleMapper, userRoleMapper, sessionMapper);
        PlatformLoginSessionEntity session = new PlatformLoginSessionEntity();
        session.setUserId(7L);
        session.setExpiresAt(LocalDateTime.now().plusHours(1));
        when(sessionMapper.selectOne(any())).thenReturn(session);
        when(userMapper.selectById(7L)).thenReturn(user(7L, "jsh", "jsh"));

        ResponseEntity<PlatformIdentityController.PlatformUserProfile> response =
                controller.me("Bearer token-1");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("jsh", response.getBody().username());
    }

    @Test
    void managesAuthProvidersUsersRolesAndRoleGrantsLocally() {
        PlatformUserMapper userMapper = mock(PlatformUserMapper.class);
        PlatformRoleMapper roleMapper = mock(PlatformRoleMapper.class);
        PlatformUserRoleMapper userRoleMapper = mock(PlatformUserRoleMapper.class);
        PlatformAuthProviderMapper authProviderMapper = mock(PlatformAuthProviderMapper.class);
        PlatformIdentityController controller = new PlatformIdentityController(
                userMapper,
                roleMapper,
                userRoleMapper,
                mock(PlatformLoginSessionMapper.class),
                authProviderMapper);
        PlatformAuthProviderEntity provider = new PlatformAuthProviderEntity();
        provider.setId(1L);
        provider.setProviderCode("LOCAL");
        provider.setProviderName("本地开发登录");
        provider.setProviderType("LOCAL");
        provider.setStatus("ACTIVE");
        provider.setConfigJson("{}");
        when(authProviderMapper.selectList(any())).thenReturn(List.of(provider));
        when(authProviderMapper.selectOne(any())).thenReturn(provider);
        when(userMapper.selectList(any())).thenReturn(List.of(user(7L, "jsh", "jsh")));
        when(roleMapper.selectList(any())).thenReturn(List.of(role(3L, "PLATFORM_ADMIN", "平台管理员")));
        when(userRoleMapper.selectList(any())).thenReturn(List.of(userRole(7L, 3L)));
        when(roleMapper.selectBatchIds(List.of(3L))).thenReturn(List.of(role(3L, "PLATFORM_ADMIN", "平台管理员")));

        assertEquals("LOCAL", controller.listAuthProviders().getBody().get(0).providerCode());
        assertEquals("LOCAL", controller.saveAuthProvider(new PlatformIdentityController.PlatformAuthProviderCommand(
                "LOCAL",
                "本地开发登录",
                "LOCAL",
                "ACTIVE",
                "{}")).getBody().providerCode());
        assertEquals("jsh", controller.listUsers().getBody().get(0).username());
        assertEquals("PLATFORM_ADMIN", controller.listRoles().getBody().get(0).roleCode());
        assertEquals("PLATFORM_ADMIN", controller.listUserRoleGrants(7L).getBody().get(0).roleCode());

        ResponseEntity<List<PlatformIdentityController.PlatformUserRoleGrantView>> saved =
                controller.saveUserRoleGrants(7L, List.of(new PlatformIdentityController.PlatformUserRoleGrantCommand(
                        3L,
                        "GLOBAL",
                        "*")));

        assertEquals(HttpStatus.OK, saved.getStatusCode());
        verify(userRoleMapper).delete(any());
        verify(userRoleMapper).insert(any());
    }

    private PlatformIdentityController controller(PlatformUserMapper userMapper,
                                                  PlatformRoleMapper roleMapper,
                                                  PlatformUserRoleMapper userRoleMapper,
                                                  PlatformLoginSessionMapper sessionMapper) {
        return new PlatformIdentityController(
                userMapper,
                roleMapper,
                userRoleMapper,
                sessionMapper,
                mock(PlatformAuthProviderMapper.class));
    }

    private PlatformUserEntity user(Long id, String username, String displayName) {
        PlatformUserEntity entity = new PlatformUserEntity();
        entity.setId(id);
        entity.setUsername(username);
        entity.setDisplayName(displayName);
        entity.setStatus("ACTIVE");
        entity.setSourceProvider("LOCAL");
        entity.setExternalSubject(username);
        return entity;
    }

    private PlatformRoleEntity role(Long id, String roleCode, String roleName) {
        PlatformRoleEntity entity = new PlatformRoleEntity();
        entity.setId(id);
        entity.setRoleCode(roleCode);
        entity.setRoleName(roleName);
        entity.setStatus("ACTIVE");
        return entity;
    }

    private PlatformUserRoleEntity userRole(Long userId, Long roleId) {
        PlatformUserRoleEntity entity = new PlatformUserRoleEntity();
        entity.setUserId(userId);
        entity.setRoleId(roleId);
        entity.setScopeType("GLOBAL");
        entity.setScopeValue("*");
        return entity;
    }
}
