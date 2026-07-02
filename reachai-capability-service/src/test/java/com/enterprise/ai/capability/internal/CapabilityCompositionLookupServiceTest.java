package com.enterprise.ai.capability.internal;

import com.enterprise.ai.agent.capability.CapabilityAssetService;
import com.enterprise.ai.agent.capability.CompositionDefinitionEntity;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CapabilityCompositionLookupServiceTest {

    private final CapabilityAssetService assetService = mock(CapabilityAssetService.class);
    private final CapabilityCompositionLookupService service = new CapabilityCompositionLookupService(assetService);

    @Test
    void returnsCompositionDefinitionByQualifiedName() {
        when(assetService.findCompositionByQualifiedName("orders.queryOrderFlow"))
                .thenReturn(Optional.of(newComposition()));

        Map<String, Object> result = service.getCompositionDefinition("orders.queryOrderFlow");

        assertEquals(22L, result.get("id"));
        assertEquals("orders", result.get("capabilityCode"));
        assertEquals("queryOrderFlow", result.get("compositionCode"));
        assertEquals("orders.queryOrderFlow", result.get("qualifiedName"));
        assertEquals("{\"entry\":\"answer\"}", result.get("graphSpecJson"));
        assertEquals(Boolean.TRUE, result.get("enabled"));
        assertEquals("2026-06-30T09:00", result.get("createTime"));
    }

    @Test
    void rejectsMissingCompositionDefinition() {
        when(assetService.findCompositionByQualifiedName("missing.flow")).thenReturn(Optional.empty());

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> service.getCompositionDefinition("missing.flow"));

        assertEquals("Composition definition not found: missing.flow", ex.getMessage());
    }

    private CompositionDefinitionEntity newComposition() {
        CompositionDefinitionEntity entity = new CompositionDefinitionEntity();
        entity.setId(22L);
        entity.setCapabilityModuleId(3L);
        entity.setCapabilityCode("orders");
        entity.setCompositionCode("queryOrderFlow");
        entity.setName("Query order flow");
        entity.setQualifiedName("orders.queryOrderFlow");
        entity.setDescription("Query order by order number");
        entity.setGraphSpecJson("{\"entry\":\"answer\"}");
        entity.setInputSchemaJson("{}");
        entity.setOutputSchemaJson("{}");
        entity.setSideEffect("READ");
        entity.setEnabled(true);
        entity.setAgentVisible(true);
        entity.setCreateTime(LocalDateTime.of(2026, 6, 30, 9, 0));
        entity.setUpdateTime(LocalDateTime.of(2026, 6, 30, 9, 5));
        return entity;
    }
}
