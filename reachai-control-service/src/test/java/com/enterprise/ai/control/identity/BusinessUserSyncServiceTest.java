package com.enterprise.ai.control.identity;

import com.enterprise.ai.control.identity.BusinessUserSyncService.AttestedBusinessUser;
import com.enterprise.ai.control.identity.BusinessUserSyncService.BusinessUserDisabledException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
    void personalConnectionOriginRequiresTheExactActiveBindingAndOwner() {
        var user = new BusinessUserEntity();
        user.setId(7L); user.setTenantId("default"); user.setStatus("ACTIVE");
        when(users.selectById(7L)).thenReturn(user);
        var binding = new ExternalUserBindingEntity();
        binding.setTenantId("default"); binding.setBusinessUserId(7L); binding.setAppId("erp");
        binding.setExternalUserId("Alice"); binding.setStatus("ACTIVE");
        when(bindings.selectOne(any())).thenReturn(binding);
        assertTrue(service.hasActiveOrigin("default", 7L, "erp", "Alice"));
        assertEquals(false, service.hasActiveOrigin("default", 7L, "erp", "alice"));
        binding.setBusinessUserId(8L);
        assertEquals(false, service.hasActiveOrigin("default", 7L, "erp", "Alice"));
        binding.setBusinessUserId(7L); binding.setStatus("DISABLED");
        assertEquals(false, service.hasActiveOrigin("default", 7L, "erp", "Alice"));
        binding.setStatus("ACTIVE"); user.setStatus("DISABLED");
        assertEquals(false, service.hasActiveOrigin("default", 7L, "erp", "Alice"));
    }

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
        assertTrue(synced.globalUserId().matches("local:[0-9a-f-]{36}"));
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

        assertTrue(synced.globalUserId().matches("local:[0-9a-f-]{36}"));
    }

    @Test
    void aClaimedGlobalIdDoesNotAutomaticallyLinkDifferentBusinessSystems() {
        var first = service.sync(new AttestedBusinessUser("default", "erp-a", "42", "claimed-global", "A", List.of()));
        var second = service.sync(new AttestedBusinessUser("default", "erp-b", "42", "claimed-global", "B", List.of()));
        assertNotEquals(first.globalUserId(), second.globalUserId());
        assertTrue(first.globalUserId().startsWith("local:"));
        assertTrue(second.globalUserId().startsWith("local:"));
        verify(users, times(2)).insert(any(BusinessUserEntity.class));
        verify(users, never()).selectOne(any());
    }

    @Test
    void existingBindingCannotResolveAnOwnerInAnotherTenantOrSilentlyRecreateAnOrphan() {
        var existing = new ExternalUserBindingEntity();
        existing.setId(3L); existing.setBusinessUserId(7L); existing.setStatus("ACTIVE");
        existing.setTenantId("tenant-a"); existing.setAppId("erp"); existing.setExternalUserId("1");
        when(bindings.selectOne(any())).thenReturn(existing);
        var wrong = new BusinessUserEntity(); wrong.setId(7L); wrong.setTenantId("tenant-b"); wrong.setStatus("ACTIVE");
        when(users.selectById(7L)).thenReturn(wrong).thenReturn(null);
        var attested = new AttestedBusinessUser("tenant-a", "erp", "1", null, "name", List.of());
        assertThrows(IllegalStateException.class, () -> service.sync(attested));
        assertThrows(IllegalStateException.class, () -> service.sync(attested));
        verify(users, never()).insert(any(BusinessUserEntity.class));
        verify(bindings, never()).updateById(any(ExternalUserBindingEntity.class));
        when(users.selectById(7L)).thenReturn(wrong);
        assertEquals(java.util.Optional.empty(), service.activeAccount("tenant-a", 7L, "erp"));
    }

    @Test
    void disabledEmployeeIsRefusedNotReactivated() {
        ExternalUserBindingEntity existing = new ExternalUserBindingEntity();
        existing.setId(3L);
        existing.setBusinessUserId(7L);
        existing.setStatus("ACTIVE");
        existing.setTenantId("default"); existing.setAppId("yudao-erp"); existing.setExternalUserId("1");
        when(bindings.selectOne(any())).thenReturn(existing);
        BusinessUserEntity disabled = new BusinessUserEntity();
        disabled.setId(7L);
        disabled.setTenantId("default");
        disabled.setStatus("DISABLED");
        when(users.selectById(7L)).thenReturn(disabled);

        assertThrows(BusinessUserDisabledException.class, () -> service.sync(
                new AttestedBusinessUser("default", "yudao-erp", "1", null, "张三", List.of())));
        verify(users, never()).updateById(any(BusinessUserEntity.class));
        verify(bindings, never()).updateById(any(ExternalUserBindingEntity.class));
    }

    @Test
    void caseInsensitiveDatabaseMatchCannotImpersonateAnotherExternalIdentity() {
        var binding = new ExternalUserBindingEntity();
        binding.setTenantId("default"); binding.setAppId("erp"); binding.setExternalUserId("Alice");
        binding.setBusinessUserId(7L); binding.setStatus("ACTIVE");
        when(bindings.selectOne(any())).thenReturn(binding);
        assertThrows(IllegalStateException.class, () -> service.sync(
                new AttestedBusinessUser("default", "erp", "alice", null, "name", List.of())));
        verify(users, never()).selectById(any());
        verify(bindings, never()).updateById(any(ExternalUserBindingEntity.class));
    }
}
