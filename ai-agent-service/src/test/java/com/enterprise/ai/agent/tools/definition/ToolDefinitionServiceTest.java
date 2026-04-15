package com.enterprise.ai.agent.tools.definition;

import com.enterprise.ai.agent.tools.ToolRegistry;
import com.enterprise.ai.skill.AiTool;
import com.enterprise.ai.skill.ToolParameter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ToolDefinitionServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

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
    void importsScannerManifestAsDynamicToolsAndRegistersEnabledTool() {
        ToolDefinitionMapper mapper = mock(ToolDefinitionMapper.class);
        ToolRegistry registry = spy(new ToolRegistry(List.of()));
        when(mapper.selectOne(any())).thenReturn(null);

        ToolDefinitionService service = new ToolDefinitionService(mapper, registry, List.of(), objectMapper);
        String yaml = """
                project:
                  name: legacy-crm
                  baseUrl: http://localhost:9001
                  contextPath: /api
                tools:
                  - name: query_customer
                    description: 查询客户
                    method: GET
                    path: /customer/search
                    endpoint: GET /api/customer/search
                    parameters:
                      - name: keyword
                        type: string
                        description: 搜索关键词
                        required: true
                        location: QUERY
                    requestBodyType:
                    responseType: CustomerList
                    source:
                      scanner: controller
                      location: CustomerController.java#search
                """;

        ToolDefinitionService.ImportResult result = service.importManifest(yaml);

        assertEquals(1, result.importedCount());
        assertTrue(result.toolNames().contains("query_customer"));
        verify(mapper).insert(any(ToolDefinitionEntity.class));
        verify(registry).register(any(AiTool.class));
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
