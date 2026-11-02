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
import static org.mockito.Mockito.doThrow;
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
    void crossSystemAccountAndRolesRequireExactActiveOwnerEvenWithCaseInsensitiveDatabaseMatches() {
        var user = new BusinessUserEntity();
        user.setId(7L); user.setTenantId("default"); user.setStatus("ACTIVE");
        when(users.selectById(7L)).thenReturn(user);
        var binding = new ExternalUserBindingEntity();
        binding.setTenantId("default"); binding.setBusinessUserId(7L); binding.setAppId("ERP");
        binding.setExternalUserId("Alice"); binding.setStatus("ACTIVE");
        when(bindings.selectOne(any())).thenReturn(binding);
        assertTrue(service.activeAccount("default", 7L, "erp").isEmpty());
        verify(roles, never()).selectList(any());

        binding.setAppId("erp");
        var own = role("default", 7L, "erp", "Alice", "reader", "ACTIVE");
        var otherCase = role("default", 7L, "erp", "alice", "admin", "ACTIVE");
        var otherOwner = role("default", 8L, "erp", "Alice", "manager", "ACTIVE");
        var disabled = role("default", 7L, "erp", "Alice", "disabled", "DISABLED");
        when(roles.selectList(any())).thenReturn(List.of(own, otherCase, otherOwner, disabled));
        assertEquals(List.of("reader"), service.activeAccount("default", 7L, "erp").orElseThrow().roles());
        binding.setBusinessUserId(8L);
        assertTrue(service.activeAccount("default", 7L, "erp").isEmpty());
    }

    @Test
    void linkedSystemsExcludeRowsOutsideTheExactActiveOwner() {
        var user = new BusinessUserEntity();
        user.setId(7L); user.setTenantId("default"); user.setStatus("ACTIVE");
        when(users.selectById(7L)).thenReturn(user);
        var valid = new ExternalUserBindingEntity();
        valid.setTenantId("default"); valid.setBusinessUserId(7L); valid.setAppId("erp");
        valid.setExternalUserId("Alice"); valid.setStatus("ACTIVE");
        var other = new ExternalUserBindingEntity();
        other.setTenantId("default"); other.setBusinessUserId(8L); other.setAppId("crm");
        other.setExternalUserId("Alice"); other.setStatus("ACTIVE");
        when(bindings.selectList(any())).thenReturn(List.of(valid, other));
        assertEquals(java.util.Set.of("erp"), service.activeAppIds("default", 7L));
        user.setStatus("DISABLED");
        assertTrue(service.activeAppIds("default", 7L).isEmpty());
    }

    private static ExternalUserRoleBindingEntity role(String tenant, long owner, String app, String external,
                                                       String code, String status) {
        var role = new ExternalUserRoleBindingEntity();
        role.setTenantId(tenant); role.setBusinessUserId(owner); role.setAppId(app);
        role.setExternalUserId(external); role.setRoleCode(code); role.setStatus(status);
        return role;
    }

    @Test
    void unchangedSyncedRoleIsKeptWithoutDeleteAndReinsert() {
        var user = new BusinessUserEntity();
        user.setId(7L); user.setTenantId("default"); user.setStatus("ACTIVE");
        var binding = new ExternalUserBindingEntity();
        binding.setId(9L); binding.setTenantId("default"); binding.setBusinessUserId(7L);
        binding.setAppId("erp"); binding.setExternalUserId("Alice"); binding.setStatus("ACTIVE");
        when(users.selectById(7L)).thenReturn(user);
        when(bindings.selectOne(any())).thenReturn(binding);
        var existing = role("default", 7L, "erp", "Alice", "reader", "ACTIVE");
        existing.setId(12L); existing.setSource("BUSINESS_SYSTEM_SYNC"); existing.setRoleName("reader");
        when(roles.selectList(any())).thenReturn(List.of(existing));

        assertEquals(List.of("reader"), service.sync(
                new AttestedBusinessUser("default", "erp", "Alice", null, "Alice", List.of("reader"))).roles());

        verify(roles, times(1)).selectList(any());
        verify(roles, never()).deleteById(any(Long.class));
        verify(roles, never()).insert(any(ExternalUserRoleBindingEntity.class));
    }

    @Test
    void roleChangesReplaceOnlyTheObsoleteSyncedRoleAndKeepManualRoles() {
        var user = new BusinessUserEntity();
        user.setId(7L); user.setTenantId("default"); user.setStatus("ACTIVE");
        var binding = new ExternalUserBindingEntity();
        binding.setId(9L); binding.setTenantId("default"); binding.setBusinessUserId(7L);
        binding.setAppId("erp"); binding.setExternalUserId("Alice"); binding.setStatus("ACTIVE");
        when(users.selectById(7L)).thenReturn(user);
        when(bindings.selectOne(any())).thenReturn(binding);
        var obsolete = role("default", 7L, "erp", "Alice", "old", "ACTIVE");
        obsolete.setId(12L); obsolete.setSource("BUSINESS_SYSTEM_SYNC"); obsolete.setRoleName("old");
        var manual = role("default", 7L, "erp", "Alice", "manual", "ACTIVE");
        manual.setId(13L); manual.setSource("MANUAL");
        when(roles.selectList(any())).thenReturn(List.of(obsolete, manual));

        service.sync(new AttestedBusinessUser("default", "erp", "Alice", null, "Alice", List.of("manual", "new")));

        verify(roles).deleteById(12L);
        verify(roles, never()).deleteById(13L);
        ArgumentCaptor<ExternalUserRoleBindingEntity> inserted = ArgumentCaptor.forClass(ExternalUserRoleBindingEntity.class);
        verify(roles).insert(inserted.capture());
        assertEquals("new", inserted.getValue().getRoleCode());
    }

    @Test
    void anotherOwnersConflictingRoleFailsClosedInsteadOfBeingAcceptedAsThisUsersRole() {
        doAnswer(invocation -> { invocation.<BusinessUserEntity>getArgument(0).setId(7L); return 1; })
                .when(users).insert(any(BusinessUserEntity.class));
        var unrelated = role("default", 8L, "erp", "Alice", "reader", "ACTIVE");
        when(roles.selectList(any())).thenReturn(List.of(unrelated));
        doThrow(new org.springframework.dao.DuplicateKeyException("conflicting role"))
                .when(roles).insert(any(ExternalUserRoleBindingEntity.class));
        assertThrows(org.springframework.dao.DuplicateKeyException.class, () ->
                service.sync(new AttestedBusinessUser("default", "erp", "Alice", null, "Alice", List.of("reader"))));
        ArgumentCaptor<ExternalUserRoleBindingEntity> inserted = ArgumentCaptor.forClass(ExternalUserRoleBindingEntity.class);
        verify(roles).insert(inserted.capture());
        assertEquals(7L, inserted.getValue().getBusinessUserId());
        assertEquals("reader", inserted.getValue().getRoleCode());
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
