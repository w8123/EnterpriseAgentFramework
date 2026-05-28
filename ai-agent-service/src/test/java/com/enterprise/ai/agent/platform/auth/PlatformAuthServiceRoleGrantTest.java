package com.enterprise.ai.agent.platform.auth;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlatformAuthServiceRoleGrantTest {

    @Test
    void replaceUserRoleGrantsNormalizesScopeAndDeduplicatesRows() {
        PlatformUserMapper userMapper = mock(PlatformUserMapper.class);
        PlatformRoleMapper roleMapper = mock(PlatformRoleMapper.class);
        PlatformUserRoleMapper userRoleMapper = mock(PlatformUserRoleMapper.class);
        PlatformAuthService service = service(userMapper, roleMapper, userRoleMapper);

        PlatformUserEntity user = new PlatformUserEntity();
        user.setId(7L);
        when(userMapper.selectById(7L)).thenReturn(user);

        PlatformRoleEntity owner = new PlatformRoleEntity();
        owner.setId(3L);
        owner.setRoleCode("PROJECT_OWNER");
        owner.setRoleName("Project Owner");
        owner.setStatus("ACTIVE");
        when(roleMapper.selectList(any())).thenReturn(List.of(owner), List.of(owner));

        PlatformUserRoleEntity saved = new PlatformUserRoleEntity();
        saved.setId(10L);
        saved.setUserId(7L);
        saved.setRoleId(3L);
        saved.setScopeType("PROJECT");
        saved.setScopeValue("42");
        when(userRoleMapper.selectList(any())).thenReturn(List.of(saved));

        List<PlatformAuthService.UserRoleGrantView> result = service.replaceUserRoleGrants(7L, List.of(
                new PlatformAuthService.UserRoleGrantCommand(3L, "project", "42"),
                new PlatformAuthService.UserRoleGrantCommand(3L, " PROJECT ", "42")));

        ArgumentCaptor<PlatformUserRoleEntity> captor = ArgumentCaptor.forClass(PlatformUserRoleEntity.class);
        verify(userRoleMapper).delete(any());
        verify(userRoleMapper).insert(captor.capture());
        assertEquals(7L, captor.getValue().getUserId());
        assertEquals(3L, captor.getValue().getRoleId());
        assertEquals("PROJECT", captor.getValue().getScopeType());
        assertEquals("42", captor.getValue().getScopeValue());
        assertEquals("PROJECT_OWNER", result.get(0).roleCode());
    }

    private PlatformAuthService service(PlatformUserMapper userMapper,
                                        PlatformRoleMapper roleMapper,
                                        PlatformUserRoleMapper userRoleMapper) {
        return new PlatformAuthService(
                new PlatformAuthProperties(),
                mock(PlatformPasswordHasher.class),
                List.of(),
                userMapper,
                roleMapper,
                userRoleMapper,
                mock(PlatformPermissionMapper.class),
                mock(PlatformRolePermissionMapper.class),
                mock(PlatformLoginSessionMapper.class),
                mock(PlatformAuthProviderConfigService.class));
    }
}
