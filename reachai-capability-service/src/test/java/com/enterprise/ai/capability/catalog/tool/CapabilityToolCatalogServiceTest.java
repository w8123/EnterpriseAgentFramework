package com.enterprise.ai.capability.catalog.tool;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectMapper;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectToolMapper;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionEntity;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionMapper;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionParameter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CapabilityToolCatalogServiceTest {

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "capability-tool-catalog-test"),
                ToolDefinitionEntity.class);
    }

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
        AtomicReference<Wrapper<ToolDefinitionEntity>> capturedQuery = new AtomicReference<>();
        when(toolMapper.selectPage(any(), any())).thenAnswer(invocation -> {
            capturedQuery.set(invocation.getArgument(1));
            return page;
        });

        IPage<ToolDefinitionEntity> result = service.page(1, 20, "order", "manual", true, 7L);

        assertEquals(1, result.getTotal());
        assertEquals("orders_create", result.getRecords().get(0).getName());
        assertTrue(capturedQuery.get().getSqlSegment().toLowerCase().contains("qualified_name"));
        var query = (com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?>) capturedQuery.get();
        assertTrue(query.getSqlSegment().toLowerCase().contains("asset_type"));
        assertTrue(query.getParamNameValuePairs().containsValue("BUSINESS_METHOD"));
        assertTrue(query.getParamNameValuePairs().containsValue("HTTP_API"));
        verify(toolMapper).selectPage(any(), any());
    }

    @Test
    void recognizesTrustedSdkBindingWithoutCreatingOrEditingProjection() {
        ToolDefinitionEntity owned = tool("orders_create", "Create order");
        owned.setSourceQualifiedName("orders:create");
        owned.setAssetType("BUSINESS_METHOD");
        owned.setSourceLocation(null);

        assertTrue(service.isSdkBackedTool(owned));
        assertEquals("BUSINESS_METHOD", owned.getAssetType());
        org.mockito.Mockito.verifyNoInteractions(toolMapper);
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
    void convertsJsonSchemaObjectToParameterList() {
        List<ToolDefinitionParameter> parameters = service.parseParameters("""
                {
                  "type": "object",
                  "properties": {
                    "query": {
                      "type": "object",
                      "title": "Query filters",
                      "sourceHint": "由订单上下文提供",
                      "properties": {
                        "pageIndex": {"type": "integer", "default": 1, "minimum": 1},
                        "pageSize": {"type": ["integer", "null"], "example": 20, "maximum": 100}
                      },
                      "required": ["pageIndex"]
                    }
                  },
                  "required": ["query"],
                  "additionalProperties": false
                }
                """);

        assertEquals(1, parameters.size());
        ToolDefinitionParameter query = parameters.get(0);
        assertEquals("query", query.name());
        assertEquals("object", query.type());
        assertEquals("Query filters", query.description());
        assertEquals(true, query.required());
        assertEquals("由订单上下文提供", ((java.util.Map<?, ?>) query.metadata()).get("sourceHint"));
        assertEquals(2, query.children().size());
        assertEquals("pageIndex", query.children().get(0).name());
        assertEquals(true, query.children().get(0).required());
        assertEquals(1, ((java.util.Map<?, ?>) query.children().get(0).metadata()).get("default"));
        assertEquals(1, ((java.util.Map<?, ?>) query.children().get(0).metadata()).get("minimum"));
        assertEquals("integer", query.children().get(1).type());
        assertEquals(20, ((java.util.Map<?, ?>) query.children().get(1).metadata()).get("example"));
        assertEquals(100, ((java.util.Map<?, ?>) query.children().get(1).metadata()).get("maximum"));
    }

    @Test
    void rejectsUnsupportedParameterJsonShape() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.parseParameters("\"not-a-parameter-contract\"")
        );

        assertEquals("invalid tool parameters json", error.getMessage());
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
        entity.setTitle("创建订单");
        entity.setDescription(description);
        entity.setSource("scanner");
        entity.setAssetType("BUSINESS_METHOD");
        entity.setHttpMethod("POST");
        entity.setEndpointPath("/orders");
        entity.setEnabled(true);
        return entity;
    }

}
