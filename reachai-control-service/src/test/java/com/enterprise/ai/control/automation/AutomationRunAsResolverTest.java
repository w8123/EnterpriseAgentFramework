package com.enterprise.ai.control.automation;

import com.enterprise.ai.control.identity.BusinessUserSyncService;
import com.enterprise.ai.control.identity.BusinessUserSyncService.BusinessSystemAccount;
import com.enterprise.ai.control.identity.RuntimeUserMappingEntity;
import com.enterprise.ai.control.identity.RuntimeUserMappingMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AutomationRunAsResolverTest {

    private final RuntimeUserMappingMapper mappings = mock(RuntimeUserMappingMapper.class);
    private final BusinessUserSyncService businessUsers = mock(BusinessUserSyncService.class);
    private final AutomationRunAsResolver resolver = new AutomationRunAsResolver(
            new com.enterprise.ai.control.identity.RuntimeUserMappingLookup(mappings), businessUsers);

    @Test
    void resolvesTheSaversMappedAccountWithItsCurrentRoles() {
        when(mappings.selectList(any())).thenReturn(List.of(mapping("yudao-erp", 3L, "staff01")));
        when(businessUsers.activeAccountByExternalUser("default", "yudao-erp", "staff01"))
                .thenReturn(Optional.of(new BusinessSystemAccount("yudao-erp", "staff01", "员工", List.of("sales"))));

        var result = resolver.resolve(new AutomationRunAsResolver.Request("Default", "platform-user:7", 3L, "yudao-erp"));

        assertTrue(result.resolved());
        assertEquals("staff01", result.externalUserId());
        assertEquals(List.of("sales"), result.roles());
        assertEquals("default", result.tenantId());
    }

    @Test
    void ignoresMappingsForOtherSystemsOrWithoutASystem() {
        when(mappings.selectList(any())).thenReturn(List.of(mapping("other", null, "x"), mapping(null, null, "y"),
                mapping("yudao-erp", 9L, "z")));

        var result = resolver.resolve(new AutomationRunAsResolver.Request("default", "platform-user:7", 3L, "yudao-erp"));

        assertFalse(result.resolved());
        assertEquals(AutomationRunAsResolver.NO_MAPPING, result.code());
        verifyNoInteractions(businessUsers);
    }

    @Test
    void refusesWhenTwoAccountsAreMappedForTheSameSystem() {
        when(mappings.selectList(any())).thenReturn(List.of(mapping("yudao-erp", 3L, "a"), mapping("yudao-erp", null, "b")));

        var result = resolver.resolve(new AutomationRunAsResolver.Request("default", "platform-user:7", 3L, "yudao-erp"));

        assertEquals(AutomationRunAsResolver.AMBIGUOUS, result.code());
        verifyNoInteractions(businessUsers);
    }

    @Test
    void refusesADisabledAccountAndNonPlatformActors() {
        when(mappings.selectList(any())).thenReturn(List.of(mapping("yudao-erp", 3L, "staff01")));
        when(businessUsers.activeAccountByExternalUser(any(), any(), any())).thenReturn(Optional.empty());

        assertEquals(AutomationRunAsResolver.ACCOUNT_INACTIVE, resolver.resolve(
                new AutomationRunAsResolver.Request("default", "platform-user:7", 3L, "yudao-erp")).code());
        assertEquals(AutomationRunAsResolver.INVALID, resolver.resolve(
                new AutomationRunAsResolver.Request("default", "automation:abc", 3L, "yudao-erp")).code());
        verify(mappings, org.mockito.Mockito.times(1)).selectList(any());
    }

    private static RuntimeUserMappingEntity mapping(String projectCode, Long projectId, String externalUserId) {
        RuntimeUserMappingEntity entity = new RuntimeUserMappingEntity();
        entity.setProjectCode(projectCode);
        entity.setProjectId(projectId);
        entity.setExternalUserId(externalUserId);
        entity.setStatus("ACTIVE");
        entity.setActiveMarker(1);
        return entity;
    }
}
