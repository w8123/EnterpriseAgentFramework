package com.enterprise.ai.capability.catalog.composition;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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

class CapabilityCompositionCatalogServiceTest {

    private final ToolDefinitionMapper mapper = mock(ToolDefinitionMapper.class);
    private final CapabilityCompositionCatalogService service = new CapabilityCompositionCatalogService(
            mapper,
            new ObjectMapper()
    );

    @Test
    void pagesOnlySkillDefinitionsFromCapabilityOwnedTable() {
        ToolDefinitionEntity entity = skill("order_composer");
        Page<ToolDefinitionEntity> page = new Page<>(1, 20, 1);
        page.setRecords(List.of(entity));
        when(mapper.selectPage(any(), any())).thenReturn(page);

        IPage<ToolDefinitionEntity> result = service.page(1, 20, "order", true, false, 7L);

        assertEquals(1, result.getTotal());
        assertEquals("order_composer", result.getRecords().get(0).getName());
        verify(mapper).selectPage(any(), any());
    }

    @Test
    void createsSubAgentCompositionWithoutRuntimeRegistration() {
        AtomicReference<ToolDefinitionEntity> inserted = new AtomicReference<>();
        when(mapper.selectOne(any())).thenReturn(null);
        when(mapper.insert(any())).thenAnswer(invocation -> {
            ToolDefinitionEntity entity = invocation.getArgument(0);
            entity.setId(12L);
            inserted.set(entity);
            return 1;
        });

        ToolDefinitionEntity created = service.create(request("order_composer"));

        assertEquals(12L, created.getId());
        ToolDefinitionEntity entity = inserted.get();
        assertNotNull(entity);
        assertEquals("order_composer", entity.getName());
        assertEquals("SKILL", entity.getKind());
        assertEquals("SUB_AGENT", entity.getSkillKind());
        assertEquals("manual", entity.getSource());
        assertEquals("WRITE", entity.getSideEffect());
        assertFalse(Boolean.TRUE.equals(entity.getDraft()));
        assertTrue(Boolean.TRUE.equals(entity.getEnabled()));
        assertEquals(null, entity.getHttpMethod());
        assertNotNull(entity.getCreateTime());
        assertNotNull(entity.getUpdateTime());
    }

    @Test
    void rejectsSubAgentWithoutRequiredSpecFields() {
        ToolDefinitionUpsertRequest bad = ToolDefinitionUpsertRequest.skill(
                "bad",
                "Bad",
                List.of(),
                "manual",
                null,
                true,
                true,
                "WRITE",
                "SUB_AGENT",
                "{\"systemPrompt\":\"\",\"toolWhitelist\":[]}",
                false
        );

        assertThrows(IllegalArgumentException.class, () -> service.create(bad));
    }

    @Test
    void updatesExistingCompositionWhileKeepingStoredNameAndSource() {
        ToolDefinitionEntity existing = skill("order_composer");
        existing.setId(12L);
        existing.setSource("scanner");
        when(mapper.selectOne(any())).thenReturn(existing);
        AtomicReference<ToolDefinitionEntity> updated = new AtomicReference<>();
        when(mapper.updateById(any())).thenAnswer(invocation -> {
            updated.set(invocation.getArgument(0));
            return 1;
        });

        ToolDefinitionEntity result = service.update("order_composer", request("ignored"));

        assertEquals(12L, result.getId());
        assertEquals("order_composer", result.getName());
        assertEquals("scanner", result.getSource());
        assertEquals("Order composition", updated.get().getDescription());
        assertNotNull(updated.get().getUpdateTime());
    }

    @Test
    void refusesToEnableDraftComposition() {
        ToolDefinitionEntity existing = skill("draft_composer");
        existing.setDraft(true);
        existing.setEnabled(false);
        when(mapper.selectOne(any())).thenReturn(existing);

        assertThrows(IllegalArgumentException.class, () -> service.toggle("draft_composer", true));
    }

    @Test
    void returnsEmptyOptionalWhenCompositionIsMissingOrNotSkill() {
        when(mapper.selectOne(any())).thenReturn(null);
        assertEquals(Optional.empty(), service.findSkillByName("missing"));

        ToolDefinitionEntity tool = new ToolDefinitionEntity();
        tool.setName("plain_tool");
        tool.setKind("TOOL");
        when(mapper.selectOne(any())).thenReturn(tool);
        assertEquals(Optional.empty(), service.findSkillByName("plain_tool"));
    }

    private ToolDefinitionEntity skill(String name) {
        ToolDefinitionEntity entity = new ToolDefinitionEntity();
        entity.setName(name);
        entity.setKind("SKILL");
        entity.setDescription("Order composition");
        entity.setParametersJson(null);
        entity.setSource("manual");
        entity.setEnabled(true);
        entity.setAgentVisible(true);
        entity.setDraft(false);
        entity.setSkillKind("SUB_AGENT");
        entity.setSpecJson(specJson());
        return entity;
    }

    private ToolDefinitionUpsertRequest request(String name) {
        return ToolDefinitionUpsertRequest.skill(
                name,
                "Order composition",
                List.of(new ToolDefinitionParameter("orderId", "string", "Order id", true, "body")),
                "manual",
                null,
                true,
                true,
                "WRITE",
                "SUB_AGENT",
                specJson(),
                false
        ).withProjectScope(7L, "orders", "PROJECT", "orders:orderComposer");
    }

    private String specJson() {
        return "{\"systemPrompt\":\"Handle order operations\",\"toolWhitelist\":[\"orders_create\"],\"maxSteps\":8}";
    }
}
