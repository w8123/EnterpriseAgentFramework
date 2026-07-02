package com.enterprise.ai.capability.internal;

import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionEntity;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionMapper;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectToolEntity;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CapabilityToolExecutionServiceTest {

    @Test
    void executesEnabledHttpToolThroughInvoker() {
        ToolDefinitionMapper mapper = mock(ToolDefinitionMapper.class);
        CapturingInvoker invoker = new CapturingInvoker(Map.of("statusCode", 200, "body", Map.of("orderStatus", "PAID")));
        CapabilityToolExecutionService service = new CapabilityToolExecutionService(mapper, invoker);
        when(mapper.selectOne(any())).thenReturn(tool("orders:queryOrder", true));

        Map<String, Object> response = service.execute("orders:queryOrder",
                Map.of("input", Map.of("orderNo", "A001")));

        assertEquals(true, response.get("success"));
        assertEquals("orders:queryOrder", response.get("qualifiedName"));
        assertEquals(Map.of("orderStatus", "PAID"), response.get("data"));
        assertEquals("POST", invoker.invocation.method());
        assertEquals("http://orders/api/orders/query", invoker.invocation.url());
        assertEquals(Map.of("orderNo", "A001"), invoker.invocation.body());
    }

    @Test
    void rejectsDisabledTool() {
        ToolDefinitionMapper mapper = mock(ToolDefinitionMapper.class);
        CapabilityToolExecutionService service = new CapabilityToolExecutionService(
                mapper,
                invocation -> Map.of());
        when(mapper.selectOne(any())).thenReturn(tool("orders:queryOrder", false));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.execute("orders:queryOrder", Map.of()));

        assertEquals("Tool definition is disabled: orders:queryOrder", ex.getMessage());
    }

    @Test
    void appendsInputAsQueryStringForGetTool() {
        ToolDefinitionMapper mapper = mock(ToolDefinitionMapper.class);
        CapturingInvoker invoker = new CapturingInvoker(Map.of("statusCode", 200, "body", Map.of("orderStatus", "PAID")));
        CapabilityToolExecutionService service = new CapabilityToolExecutionService(mapper, invoker);
        ToolDefinitionEntity tool = tool("orders:queryOrder", true);
        tool.setHttpMethod("GET");
        when(mapper.selectOne(any())).thenReturn(tool);

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("orderNo", "A001");
        input.put("verbose", true);
        service.execute("orders:queryOrder", Map.of("input", input));

        assertEquals("GET", invoker.invocation.method());
        assertEquals("http://orders/api/orders/query?orderNo=A001&verbose=true", invoker.invocation.url());
        assertEquals(Map.of(), invoker.invocation.body());
    }

    @Test
    void executesScanProjectHttpToolThroughInvoker() {
        ToolDefinitionMapper mapper = mock(ToolDefinitionMapper.class);
        CapturingInvoker invoker = new CapturingInvoker(Map.of("statusCode", 200, "body", Map.of("orderStatus", "PAID")));
        CapabilityToolExecutionService service = new CapabilityToolExecutionService(mapper, invoker);
        ScanProjectToolEntity tool = scanTool(11L, true);

        Map<String, Object> response = service.execute(tool, Map.of("input", Map.of("orderNo", "A001")));

        assertEquals(true, response.get("success"));
        assertEquals(11L, response.get("scanToolId"));
        assertEquals("orders_create", response.get("toolName"));
        assertEquals(Map.of("orderStatus", "PAID"), response.get("data"));
        assertEquals("POST", invoker.invocation.method());
        assertEquals("http://orders/api/orders/create", invoker.invocation.url());
        assertEquals(Map.of("orderNo", "A001"), invoker.invocation.body());
    }

    private ToolDefinitionEntity tool(String qualifiedName, boolean enabled) {
        ToolDefinitionEntity entity = new ToolDefinitionEntity();
        entity.setId(9L);
        entity.setName("queryOrder");
        entity.setKind("TOOL");
        entity.setQualifiedName(qualifiedName);
        entity.setEnabled(enabled);
        entity.setHttpMethod("POST");
        entity.setBaseUrl("http://orders");
        entity.setContextPath("/api");
        entity.setEndpointPath("/orders/query");
        entity.setRequestBodyType("json");
        entity.setResponseType("json");
        return entity;
    }

    private ScanProjectToolEntity scanTool(Long id, boolean enabled) {
        ScanProjectToolEntity entity = new ScanProjectToolEntity();
        entity.setId(id);
        entity.setName("orders_create");
        entity.setEnabled(enabled);
        entity.setHttpMethod("POST");
        entity.setBaseUrl("http://orders");
        entity.setContextPath("/api");
        entity.setEndpointPath("/orders/create");
        entity.setRequestBodyType("json");
        entity.setResponseType("json");
        return entity;
    }

    private static final class CapturingInvoker implements CapabilityHttpToolInvoker {
        private final Map<String, Object> response;
        private CapabilityHttpToolInvocation invocation;

        private CapturingInvoker(Map<String, Object> response) {
            this.response = response;
        }

        @Override
        public Map<String, Object> invoke(CapabilityHttpToolInvocation invocation) {
            this.invocation = invocation;
            return response;
        }
    }
}
