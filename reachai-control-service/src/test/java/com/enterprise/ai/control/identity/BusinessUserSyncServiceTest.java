package com.enterprise.ai.control.identity;

import com.enterprise.ai.control.identity.BusinessUserSyncService.AttestedBusinessUser;
import com.enterprise.ai.control.identity.BusinessUserSyncService.BusinessUserDisabledException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BusinessUserSyncServiceTest {

    private final BusinessUserMapper users = mock(BusinessUserMapper.class);
    private final ExternalUserBindingMapper bindings = mock(ExternalUserBindingMapper.class);
    private final ExternalUserRoleBindingMapper roles = mock(ExternalUserRoleBindingMapper.class);
    private final BusinessUserSyncService service = new BusinessUserSyncService(users, bindings, roles);

    @Test
    void firstSeenEmployeeIsCreatedWithBindingAndSyncedRoles() {
        doAnswer(invocation -> {
            invocation.<BusinessUserEntity>getArgument(0).setId(7L);
            return 1;
        }).when(users).insert(any(BusinessUserEntity.class));

        var synced = service.sync(new AttestedBusinessUser("Tenant-A", "yudao-erp", "1", null, "张三",
                List.of("erp_user", " erp_user ", "")));

        assertEquals(7L, synced.businessUserId());
        assertEquals("tenant-a", synced.tenantId());
        assertEquals("yudao-erp:1", synced.globalUserId());
        assertEquals(List.of("erp_user"), synced.roles());
        ArgumentCaptor<BusinessUserEntity> user = ArgumentCaptor.forClass(BusinessUserEntity.class);
        verify(users).insert(user.capture());
        assertEquals("张三", user.getValue().getDisplayName());
        assertEquals("BUSINESS_SYSTEM_SYNC", user.getValue().getSource());
        ArgumentCaptor<ExternalUserBindingEntity> binding = ArgumentCaptor.forClass(ExternalUserBindingEntity.class);
        verify(bindings).insert(binding.capture());
        assertEquals(7L, binding.getValue().getBusinessUserId());
        assertEquals("1", binding.getValue().getExternalUserId());
        verify(roles, times(1)).insert(any(ExternalUserRoleBindingEntity.class));
    }

    @Test
    void externalIdEchoedAsGlobalIdStaysScopedToItsSystem() {
        var synced = service.sync(new AttestedBusinessUser("default", "yudao-erp", "1", "1", "张三", List.of()));

        assertEquals("yudao-erp:1", synced.globalUserId());
    }

    @Test
    void disabledEmployeeIsRefusedNotReactivated() {
        ExternalUserBindingEntity existing = new ExternalUserBindingEntity();
        existing.setId(3L);
        existing.setBusinessUserId(7L);
        existing.setStatus("ACTIVE");
        when(bindings.selectOne(any())).thenReturn(existing);
        BusinessUserEntity disabled = new BusinessUserEntity();
        disabled.setId(7L);
        disabled.setStatus("DISABLED");
        when(users.selectById(7L)).thenReturn(disabled);

        assertThrows(BusinessUserDisabledException.class, () -> service.sync(
                new AttestedBusinessUser("default", "yudao-erp", "1", null, "张三", List.of())));
        verify(users, never()).updateById(any(BusinessUserEntity.class));
        verify(bindings, never()).updateById(any(ExternalUserBindingEntity.class));
    }
}
