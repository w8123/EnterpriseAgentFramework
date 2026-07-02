package com.enterprise.ai.capability.catalog.tool;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectMapper;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectToolMapper;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionEntity;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionMapper;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionParameter;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionUpsertRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CapabilityToolCatalogServiceTest {

    private final ToolDefinitionMapper toolMapper = mock(ToolDefinitionMapper.class);
    private final ScanProjectMapper projectMapper = mock(ScanProjectMapper.class);
    private final ScanProjectToolMapper scanToolMapper = mock(ScanProjectToolMapper.class);
    private final CapabilityToolCatalogService service = new CapabilityToolCatalogService(
            toolMapper,
            projectMapper,
            scanToolMapper,
            new ObjectMapper()
    );

    @Test
    void pagesToolDefinitionsFromCapabilityOwnedTable() {
        ToolDefinitionEntity entity = tool("orders_create", "Create order");
        Page<ToolDefinitionEntity> page = new Page<>(1, 20, 1);
        page.setRecords(List.of(entity));
        when(toolMapper.selectPage(any(), any())).thenReturn(page);

        IPage<ToolDefinitionEntity> result = service.page(1, 20, "order", "manual", true, 7L);

        assertEquals(1, result.getTotal());
        assertEquals("orders_create", result.getRecords().get(0).getName());
        verify(toolMapper).selectPage(any(), any());
    }

    @Test
    void createsManualToolDefinitionWithoutRuntimeRegistration() {
        AtomicReference<ToolDefinitionEntity> inserted = new AtomicReference<>();
        when(toolMapper.selectOne(any())).thenReturn(null);
        when(toolMapper.insert(any())).thenAnswer(invocation -> {
            ToolDefinitionEntity entity = invocation.getArgument(0);
            entity.setId(11L);
            inserted.set(entity);
            return 1;
        });

        ToolDefinitionEntity created = service.create(toolRequest("orders_create"));

        assertEquals(11L, created.getId());
        ToolDefinitionEntity entity = inserted.get();
        assertNotNull(entity);
        assertEquals("orders_create", entity.getName());
        assertEquals("TOOL", entity.getKind());
        assertEquals("manual", entity.getSource());
        assertEquals("WRITE", entity.getSideEffect());
        assertFalse(Boolean.TRUE.equals(entity.getDraft()));
        assertNotNull(entity.getCreateTime());
        assertNotNull(entity.getUpdateTime());
    }

    @Test
    void rejectsDuplicateToolNameOnCreate() {
        when(toolMapper.selectOne(any())).thenReturn(tool("orders_create", "Create order"));

        assertThrows(IllegalArgumentException.class, () -> service.create(toolRequest("orders_create")));
    }

    @Test
    void updatesExistingToolWhileKeepingStoredNameAndSource() {
        ToolDefinitionEntity existing = tool("orders_create", "Old");
        existing.setId(11L);
        existing.setSource("scanner");
        when(toolMapper.selectOne(any())).thenReturn(existing);
        AtomicReference<ToolDefinitionEntity> updated = new AtomicReference<>();
        when(toolMapper.updateById(any())).thenAnswer(invocation -> {
            updated.set(invocation.getArgument(0));
            return 1;
        });

        ToolDefinitionEntity result = service.update("orders_create", toolRequest("ignored_new_name"));

        assertEquals(11L, result.getId());
        assertEquals("orders_create", result.getName());
        assertEquals("scanner", result.getSource());
        assertEquals("Create order", updated.get().getDescription());
        assertNotNull(updated.get().getUpdateTime());
    }

    @Test
    void togglesEnabledFlag() {
        ToolDefinitionEntity existing = tool("orders_create", "Create order");
        existing.setEnabled(true);
        when(toolMapper.selectOne(any())).thenReturn(existing);

        ToolDefinitionEntity toggled = service.toggle("orders_create", false);

        assertEquals(false, toggled.getEnabled());
        verify(toolMapper).updateById(existing);
    }

    @Test
    void refusesToDeleteCodeOwnedTool() {
        ToolDefinitionEntity existing = tool("code_tool", "Code tool");
        existing.setSource("code");
        when(toolMapper.selectOne(any())).thenReturn(existing);

        assertThrows(IllegalArgumentException.class, () -> service.delete("code_tool"));
    }

    @Test
    void deletesNonCodeTool() {
        ToolDefinitionEntity existing = tool("orders_create", "Create order");
        existing.setId(11L);
        existing.setSource("manual");
        when(toolMapper.selectOne(any())).thenReturn(existing);
        when(toolMapper.deleteById(11L)).thenReturn(1);

        assertTrue(service.delete("orders_create"));
        verify(toolMapper).deleteById(11L);
    }

    @Test
    void parsesStoredParameters() {
        List<ToolDefinitionParameter> parameters = service.parseParameters("""
                [{"name":"orderId","type":"string","description":"Order id","required":true,"location":"body"}]
                """);

        assertEquals(1, parameters.size());
        assertEquals("orderId", parameters.get(0).name());
    }

    @Test
    void returnsEmptyOptionalWhenToolIsMissing() {
        when(toolMapper.selectOne(any())).thenReturn(null);

        Optional<ToolDefinitionEntity> result = service.findByName("missing");

        assertTrue(result.isEmpty());
    }

    private ToolDefinitionEntity tool(String name, String description) {
        ToolDefinitionEntity entity = new ToolDefinitionEntity();
        entity.setName(name);
        entity.setKind("TOOL");
        entity.setDescription(description);
        entity.setSource("manual");
        entity.setHttpMethod("POST");
        entity.setEndpointPath("/orders");
        entity.setEnabled(true);
        entity.setAgentVisible(true);
        entity.setLightweightEnabled(false);
        return entity;
    }

    private ToolDefinitionUpsertRequest toolRequest(String name) {
        return new ToolDefinitionUpsertRequest(
                name,
                "Create order",
                List.of(new ToolDefinitionParameter("orderId", "string", "Order id", true, "body")),
                "manual",
                "manual",
                "POST",
                "http://orders.local",
                "/orders",
                "/create",
                "JSON",
                "JSON",
                7L,
                "orders",
                "PROJECT",
                "orders:createOrder",
                true,
                true,
                false
        );
    }
}
