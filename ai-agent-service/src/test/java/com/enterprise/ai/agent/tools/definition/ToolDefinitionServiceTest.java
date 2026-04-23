package com.enterprise.ai.agent.tools.definition;

import com.enterprise.ai.agent.tools.ToolRegistry;
import com.enterprise.ai.skill.AiTool;
import com.enterprise.ai.skill.ToolParameter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ToolDefinitionServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void noLongerSupportsManifestImport() {
        boolean hasImportMethod = Arrays.stream(ToolDefinitionService.class.getDeclaredMethods())
                .anyMatch(method -> "importManifest".equals(method.getName()));

        assertFalse(hasImportMethod);
    }

    @Test
    void syncCodeToolsUpsertsMetadataWithoutOverwritingFlags() {
        ToolDefinitionMapper mapper = mock(ToolDefinitionMapper.class);
        ToolRegistry registry = new ToolRegistry(List.of());
        AiTool codeTool = new DemoCodeTool();

        ToolDefinitionEntity existing = new ToolDefinitionEntity();
        existing.setId(9L);
        existing.setName("search_knowledge");
        existing.setSource("code");
        existing.setEnabled(false);
        existing.setAgentVisible(false);
        existing.setLightweightEnabled(true);

        when(mapper.selectOne(any())).thenReturn(existing);
        when(mapper.selectList(any())).thenReturn(List.of(existing));

        ToolDefinitionService service = new ToolDefinitionService(mapper, registry, List.of(codeTool), objectMapper);
        service.syncCodeTools();

        ArgumentCaptor<ToolDefinitionEntity> captor = ArgumentCaptor.forClass(ToolDefinitionEntity.class);
        verify(mapper).updateById(captor.capture());
        ToolDefinitionEntity updated = captor.getValue();
        assertEquals("search_knowledge", updated.getName());
        assertFalse(updated.getEnabled());
        assertFalse(updated.getAgentVisible());
        assertTrue(updated.getLightweightEnabled());
    }

    @Test
    void updatesCodeToolFlagsWithoutRejectingCodeSource() {
        ToolDefinitionMapper mapper = mock(ToolDefinitionMapper.class);
        ToolRegistry registry = new ToolRegistry(List.of());

        ToolDefinitionEntity existing = new ToolDefinitionEntity();
        existing.setId(11L);
        existing.setName("search_knowledge");
        existing.setDescription("搜索知识库");
        existing.setSource("code");
        existing.setEnabled(true);
        existing.setAgentVisible(true);
        existing.setLightweightEnabled(true);

        when(mapper.selectOne(any())).thenReturn(existing);

        ToolDefinitionService service = new ToolDefinitionService(mapper, registry, List.of(), objectMapper);

        ToolDefinitionEntity updated = service.update("search_knowledge", new ToolDefinitionUpsertRequest(
                "search_knowledge",
                "搜索知识库",
                List.of(new ToolDefinitionParameter("query", "string", "问题", true, null)),
                "code",
                DemoCodeTool.class.getName(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                false,
                false
        ));

        assertFalse(updated.getEnabled());
        assertFalse(updated.getAgentVisible());
        assertFalse(updated.getLightweightEnabled());
        verify(mapper).updateById(existing);
    }

    @Test
    void exposesOnlyEnabledLightweightTools() {
        ToolDefinitionMapper mapper = mock(ToolDefinitionMapper.class);
        ToolRegistry registry = new ToolRegistry(List.of());

        ToolDefinitionEntity enabled = new ToolDefinitionEntity();
        enabled.setName("search_knowledge");
        enabled.setEnabled(true);
        enabled.setLightweightEnabled(true);

        ToolDefinitionEntity disabled = new ToolDefinitionEntity();
        disabled.setName("call_business_api");
        disabled.setEnabled(false);
        disabled.setLightweightEnabled(true);

        when(mapper.selectList(any())).thenReturn(List.of(enabled, disabled));

        ToolDefinitionService service = new ToolDefinitionService(mapper, registry, List.of(), objectMapper);

        assertEquals(List.of("search_knowledge"), service.listLightweightEnabledToolNames());
        assertFalse(service.isAgentCallable("search_knowledge"));
        assertFalse(service.isLightweightCallable("call_business_api"));
    }

    @Test
    void returnsManualToolDefinitionByName() {
        ToolDefinitionMapper mapper = mock(ToolDefinitionMapper.class);
        ToolRegistry registry = new ToolRegistry(List.of());

        ToolDefinitionEntity definition = new ToolDefinitionEntity();
        definition.setName("manual_tool");
        when(mapper.selectOne(any())).thenReturn(definition);

        ToolDefinitionService service = new ToolDefinitionService(mapper, registry, List.of(), objectMapper);

        Optional<ToolDefinitionEntity> result = service.findByName("manual_tool");

        assertTrue(result.isPresent());
        assertEquals("manual_tool", result.get().getName());
        verify(mapper).selectOne(any());
        verify(mapper, never()).selectById(any());
        verify(mapper, never()).delete(any());
        verify(mapper, never()).update(eq(null), any());
    }

    @Test
    void createsToolWithProjectAssociation() {
        ToolDefinitionMapper mapper = mock(ToolDefinitionMapper.class);
        ToolRegistry registry = new ToolRegistry(List.of());
        when(mapper.selectOne(any())).thenReturn(null);

        ToolDefinitionService service = new ToolDefinitionService(mapper, registry, List.of(), objectMapper);
        service.create(new ToolDefinitionUpsertRequest(
                "legacy_crm__query_customer",
                "查询客户",
                List.of(new ToolDefinitionParameter("keyword", "string", "关键词", true, "QUERY")),
                "scanner",
                "CustomerController#queryCustomer",
                "GET",
                "http://localhost:9001",
                "/api",
                "/customer/search",
                null,
                "CustomerList",
                7L,
                false,
                false,
                false
        ));

        ArgumentCaptor<ToolDefinitionEntity> captor = ArgumentCaptor.forClass(ToolDefinitionEntity.class);
        verify(mapper).insert(captor.capture());
        ToolDefinitionEntity inserted = captor.getValue();
        assertNotNull(inserted);
        assertEquals(7L, inserted.getProjectId());
        assertEquals("legacy_crm__query_customer", inserted.getName());
    }

    @Test
    void listsAndDeletesToolsByProjectId() {
        ToolDefinitionMapper mapper = mock(ToolDefinitionMapper.class);
        ToolRegistry registry = new ToolRegistry(List.of());

        ToolDefinitionEntity first = new ToolDefinitionEntity();
        first.setId(1L);
        first.setName("legacy_crm__query_customer");
        first.setProjectId(7L);
        first.setSource("scanner");

        ToolDefinitionEntity second = new ToolDefinitionEntity();
        second.setId(2L);
        second.setName("legacy_crm__create_order");
        second.setProjectId(7L);
        second.setSource("scanner");

        when(mapper.selectList(any())).thenReturn(List.of(first, second));

        ToolDefinitionService service = new ToolDefinitionService(mapper, registry, List.of(), objectMapper);

        List<ToolDefinitionEntity> tools = service.listByProjectId(7L);
        boolean deleted = service.deleteByProjectId(7L);

        assertEquals(2, tools.size());
        assertTrue(deleted);
        verify(mapper, times(2)).selectList(any());
        verify(mapper).deleteById(1L);
        verify(mapper).deleteById(2L);
    }

    private static final class DemoCodeTool implements AiTool {
        @Override
        public String name() {
            return "search_knowledge";
        }

        @Override
        public String description() {
            return "搜索知识库";
        }

        @Override
        public Object execute(Map<String, Object> args) {
            return "ok";
        }

        @Override
        public List<ToolParameter> parameters() {
            return List.of(ToolParameter.required("query", "string", "问题"));
        }
    }
}
