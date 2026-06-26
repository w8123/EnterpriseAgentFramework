package com.enterprise.ai.agent.platform.control.identity;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BusinessUserDirectoryServiceTest {

    @Test
    void pageDirectoryUsersIncludesBindingAndRoleSummary() {
        BusinessUserMapper userMapper = mock(BusinessUserMapper.class);
        ExternalUserBindingMapper bindingMapper = mock(ExternalUserBindingMapper.class);
        ExternalUserRoleBindingMapper roleMapper = mock(ExternalUserRoleBindingMapper.class);
        BusinessUserDirectoryService service = new BusinessUserDirectoryService(userMapper, bindingMapper, roleMapper);

        BusinessUserEntity user = new BusinessUserEntity();
        user.setId(10L);
        user.setTenantId("default");
        user.setGlobalUserId("emp-001");
        user.setDisplayName("Zhang San");
        user.setStatus("ACTIVE");
        user.setLastSeenAt(LocalDateTime.now());
        Page<BusinessUserEntity> page = new Page<>(1, 20);
        page.setTotal(1);
        page.setRecords(List.of(user));
        when(userMapper.selectPage(any(Page.class), any())).thenReturn(page);

        ExternalUserBindingEntity binding = new ExternalUserBindingEntity();
        binding.setId(20L);
        binding.setBusinessUserId(10L);
        binding.setAppId("bzsdk");
        binding.setExternalUserId("ADMIN001");
        binding.setStatus("ACTIVE");
        when(bindingMapper.selectList(any())).thenReturn(List.of(binding));

        ExternalUserRoleBindingEntity role = new ExternalUserRoleBindingEntity();
        role.setBusinessUserId(10L);
        role.setAppId("bzsdk");
        role.setExternalUserId("ADMIN001");
        role.setRoleCode("admin");
        role.setStatus("ACTIVE");
        when(roleMapper.selectList(any())).thenReturn(List.of(role));

        Page<BusinessUserDirectoryService.BusinessUserView> result =
                service.pageDirectoryUsers(1, 20, "default", null, null);

        assertEquals(1, result.getTotal());
        BusinessUserDirectoryService.BusinessUserView view = result.getRecords().get(0);
        assertEquals("emp-001", view.globalUserId());
        assertEquals(1, view.bindingCount());
        assertEquals(List.of("bzsdk:ADMIN001"), view.externalIdentities());
        assertEquals(List.of("bzsdk:admin"), view.roleCodes());
    }

    @Test
    void updateBusinessUserChangesManualDirectoryFields() {
        BusinessUserMapper userMapper = mock(BusinessUserMapper.class);
        ExternalUserBindingMapper bindingMapper = mock(ExternalUserBindingMapper.class);
        ExternalUserRoleBindingMapper roleMapper = mock(ExternalUserRoleBindingMapper.class);
        BusinessUserDirectoryService service = new BusinessUserDirectoryService(userMapper, bindingMapper, roleMapper);

        BusinessUserEntity user = new BusinessUserEntity();
        user.setId(10L);
        user.setTenantId("default");
        user.setGlobalUserId("emp-001");
        user.setDisplayName("Old Name");
        user.setStatus("ACTIVE");
        when(userMapper.selectById(10L)).thenReturn(user);

        BusinessUserEntity updated = service.updateBusinessUser(
                10L,
                new BusinessUserDirectoryService.BusinessUserUpdateCommand(
                        "emp-001",
                        "New Name",
                        "new@example.com",
                        "13800000000",
                        "DISABLED"));

        assertEquals("New Name", updated.getDisplayName());
        assertEquals("new@example.com", updated.getEmail());
        assertEquals("13800000000", updated.getMobile());
        assertEquals("DISABLED", updated.getStatus());
        verify(userMapper).updateById(user);
    }

    @Test
    void embedTokenPassiveUpsertRejectsDisabledExternalBinding() {
        BusinessUserMapper userMapper = mock(BusinessUserMapper.class);
        ExternalUserBindingMapper bindingMapper = mock(ExternalUserBindingMapper.class);
        ExternalUserRoleBindingMapper roleMapper = mock(ExternalUserRoleBindingMapper.class);
        BusinessUserDirectoryService service = new BusinessUserDirectoryService(userMapper, bindingMapper, roleMapper);

        ExternalUserBindingEntity binding = new ExternalUserBindingEntity();
        binding.setId(20L);
        binding.setTenantId("default");
        binding.setAppId("bzsdk");
        binding.setExternalUserId("ADMIN001");
        binding.setStatus("DISABLED");
        when(bindingMapper.selectOne(any())).thenReturn(binding);

        BusinessPrincipal principal = BusinessPrincipal.builder()
                .tenantId("default")
                .appId("bzsdk")
                .externalUserId("ADMIN001")
                .globalUserId("emp-001")
                .userName("Zhang San")
                .build();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.upsertFromPrincipal(principal));

        assertEquals("external user is DISABLED and cannot use embedded agents", ex.getMessage());
        verify(bindingMapper, never()).updateById(binding);
    }
}
