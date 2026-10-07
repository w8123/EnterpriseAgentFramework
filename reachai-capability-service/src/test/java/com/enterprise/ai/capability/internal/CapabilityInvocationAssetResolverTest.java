package com.enterprise.ai.capability.internal;

import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionEntity;
import com.enterprise.ai.capability.catalog.businessmethod.BusinessMethodCatalogService;
import com.enterprise.ai.common.capability.CapabilityInvocationFailureCategory;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CapabilityInvocationAssetResolverTest {
    @Test void resolvesOnlyTheAcceptedBusinessMethodOwner() {
        var catalog = mock(BusinessMethodCatalogService.class);
        var fixture = new ToolDefinitionEntity();
        fixture.setId(12L); fixture.setQualifiedName("orders:queryOrder"); fixture.setName("orders_queryOrder");
        fixture.setTitle("Query order"); fixture.setProjectCode("orders"); fixture.setSideEffect("READ_ONLY");
        fixture.setEnabled(true);
        when(catalog.find("orders:queryOrder")).thenReturn(Optional.of(BusinessMethodExecutionFixtures.definition(fixture)));
        var resolved = new CapabilityInvocationAssetResolver(catalog).require("orders:queryOrder");
        assertEquals(CapabilityInvocationAsset.Source.BUSINESS_METHOD, resolved.source());
        assertEquals("CATALOG_HTTP", resolved.executorType()); assertEquals("Query order", resolved.title());
        assertEquals("orders", resolved.capabilityCode());
    }
    @Test void aMissingOwnerCannotFallBackToAnIndependentKernelAsset() {
        var catalog = mock(BusinessMethodCatalogService.class);
        when(catalog.find("system.echo")).thenReturn(Optional.empty());
        var error = assertThrows(CapabilityInvocationPolicyException.class,
                () -> new CapabilityInvocationAssetResolver(catalog).require("system.echo"));
        assertEquals(CapabilityInvocationFailureCategory.NOT_FOUND, error.category());
    }
}
