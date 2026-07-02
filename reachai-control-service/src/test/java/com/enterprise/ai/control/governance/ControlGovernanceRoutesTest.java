package com.enterprise.ai.control.governance;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentCaptor.forClass;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ControlGovernanceRoutesTest {

    @Test
    void managesToolAclRoutesWithoutFallingThroughToRetiredProxy() {
        ControlToolAclMapper mapper = mock(ControlToolAclMapper.class);
        ControlToolAclController controller = new ControlToolAclController(mapper);
        ControlToolAclEntity rule = new ControlToolAclEntity();
        rule.setId(1L);
        rule.setRoleCode("admin");
        rule.setTargetKind("ALL");
        rule.setTargetName("*");
        rule.setPermission("ALLOW");
        rule.setEnabled(true);
        Page<ControlToolAclEntity> page = new Page<>(1, 20);
        page.setRecords(List.of(rule));
        page.setTotal(1);
        when(mapper.selectPage(any(), any())).thenReturn(page);
        when(mapper.selectList(any())).thenReturn(List.of(rule));
        when(mapper.selectById(1L)).thenReturn(rule);
        when(mapper.selectOne(any())).thenReturn(null);

        ResponseEntity<Page<ControlToolAclEntity>> listed = controller.page(1, 20, "admin", "ALL");
        ResponseEntity<List<String>> roles = controller.roles();
        ResponseEntity<ControlToolAclEntity> created = controller.create(rule);
        ResponseEntity<ControlToolAclEntity> toggled = controller.toggle(1L, new ControlToolAclController.ToggleRequest(false));
        ResponseEntity<Map<String, Object>> batch = controller.grantBatch(new ControlToolAclController.GrantBatchRequest(
                "ops",
                "ALLOW",
                List.of(new ControlToolAclController.ToolAclTargetRef("TOOL", "orders.search")),
                "unit"));
        ResponseEntity<Map<String, String>> explain = controller.explain(new ControlToolAclController.ExplainRequest(
                List.of("admin"),
                List.of(new ControlToolAclController.ToolAclTargetRef("TOOL", "orders.search"))));
        ResponseEntity<Map<String, Object>> deleted = controller.delete(1L);

        assertEquals(HttpStatus.OK, listed.getStatusCode());
        assertEquals(1, listed.getBody().getRecords().size());
        assertEquals(List.of("admin"), roles.getBody());
        assertEquals("admin", created.getBody().getRoleCode());
        assertEquals(false, toggled.getBody().getEnabled());
        assertEquals(1, batch.getBody().get("count"));
        assertEquals("ALLOW", explain.getBody().get("orders.search"));
        assertEquals(true, deleted.getBody().get("ok"));
        verify(mapper, times(2)).insert(any());
        verify(mapper).updateById(rule);
        verify(mapper).deleteById(1L);
    }

    @Test
    void managesMcpAdminRoutesWithoutFallingThroughToRetiredProxy() {
        ControlMcpClientMapper clientMapper = mock(ControlMcpClientMapper.class);
        ControlMcpVisibilityMapper visibilityMapper = mock(ControlMcpVisibilityMapper.class);
        ControlMcpCallLogMapper callLogMapper = mock(ControlMcpCallLogMapper.class);
        ControlMcpAdminController controller = new ControlMcpAdminController(clientMapper, visibilityMapper, callLogMapper);
        ControlMcpClientEntity client = new ControlMcpClientEntity();
        client.setId(2L);
        client.setName("Codex");
        client.setApiKeyPrefix("mcp_test");
        client.setRolesJson("[\"admin\"]");
        client.setToolWhitelistJson("[\"orders.search\"]");
        client.setEnabled(true);
        when(clientMapper.selectList(any())).thenReturn(List.of(client));
        when(clientMapper.selectById(2L)).thenReturn(client);
        ControlMcpVisibilityEntity visibility = new ControlMcpVisibilityEntity();
        visibility.setId(3L);
        visibility.setTargetKind("TOOL");
        visibility.setTargetName("orders.search");
        visibility.setExposed(true);
        when(visibilityMapper.selectList(any())).thenReturn(List.of(visibility));
        when(visibilityMapper.selectOne(any())).thenReturn(null);
        Page<ControlMcpCallLogEntity> logs = new Page<>(1, 50);
        logs.setRecords(List.of(new ControlMcpCallLogEntity()));
        logs.setTotal(1);
        when(callLogMapper.selectPage(any(), any())).thenReturn(logs);

        ResponseEntity<List<ControlMcpClientEntity>> clients = controller.listClients();
        ResponseEntity<Map<String, Object>> created = controller.createClient(new ControlMcpAdminController.CreateClientRequest(
                "Cursor",
                List.of("admin"),
                List.of("orders.search"),
                null));
        ResponseEntity<ControlMcpClientEntity> updated = controller.updateClient(2L, new ControlMcpAdminController.UpdateClientRequest(
                "Codex Updated",
                List.of("ops"),
                List.of(),
                false,
                null));
        ResponseEntity<List<ControlMcpVisibilityEntity>> visibilityList = controller.listVisibility();
        ResponseEntity<ControlMcpVisibilityEntity> setVisibility = controller.setVisibility(new ControlMcpAdminController.SetVisibilityRequest(
                "TOOL",
                "orders.search",
                true,
                "unit"));
        ResponseEntity<Page<ControlMcpCallLogEntity>> callLogs = controller.pageLogs(1, 50, 2L, "tools/call", true, 7);
        ResponseEntity<Map<String, Object>> deleted = controller.deleteClient(2L);

        assertEquals("Codex Updated", clients.getBody().get(0).getName());
        assertNotNull(created.getBody().get("plaintextApiKey"));
        assertEquals("Codex Updated", updated.getBody().getName());
        assertEquals("orders.search", visibilityList.getBody().get(0).getTargetName());
        assertEquals(true, setVisibility.getBody().getExposed());
        assertEquals(1, callLogs.getBody().getTotal());
        assertEquals(true, deleted.getBody().get("ok"));
        verify(clientMapper).insert(any());
        verify(clientMapper).updateById(client);
        verify(clientMapper).deleteById(2L);
    }

    @Test
    void servesMcpManifestAndToolsListWithoutFallingThroughToRetiredProxy() {
        ControlMcpClientMapper clientMapper = mock(ControlMcpClientMapper.class);
        ControlMcpVisibilityMapper visibilityMapper = mock(ControlMcpVisibilityMapper.class);
        ControlMcpCallLogMapper callLogMapper = mock(ControlMcpCallLogMapper.class);
        ControlMcpEndpointController controller = new ControlMcpEndpointController(
                clientMapper,
                visibilityMapper,
                callLogMapper,
                mock(RuntimeProxyClient.class));
        ControlMcpClientEntity client = new ControlMcpClientEntity();
        client.setId(2L);
        client.setName("Codex");
        client.setApiKeyHash("e2540177bcb31c311dd7ee0b0693cc6b8affd0697099b673fdc4c67a4b9390f4");
        client.setEnabled(true);
        client.setToolWhitelistJson("[\"orders.search\"]");
        when(clientMapper.selectOne(any())).thenReturn(client);
        ControlMcpVisibilityEntity visibility = new ControlMcpVisibilityEntity();
        visibility.setTargetKind("TOOL");
        visibility.setTargetName("orders.search");
        visibility.setExposed(true);
        visibility.setNote("Search orders");
        when(visibilityMapper.selectList(any())).thenReturn(List.of(visibility));

        ResponseEntity<Map<String, Object>> manifest = controller.manifest();
        ResponseEntity<Map<String, Object>> tools = controller.jsonRpc(
                "Bearer mcp_test",
                Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/list"));

        assertEquals(HttpStatus.OK, manifest.getStatusCode());
        assertEquals("ReachAI MCP", manifest.getBody().get("name"));
        assertEquals("/mcp/jsonrpc", ((Map<?, ?>) manifest.getBody().get("transport")).get("url"));
        assertEquals(HttpStatus.OK, tools.getStatusCode());
        assertEquals("2.0", tools.getBody().get("jsonrpc"));
        assertEquals(1, tools.getBody().get("id"));
        Map<?, ?> result = (Map<?, ?>) tools.getBody().get("result");
        List<?> returnedTools = (List<?>) result.get("tools");
        Map<?, ?> tool = (Map<?, ?>) returnedTools.get(0);
        assertEquals("orders.search", tool.get("name"));
        assertEquals("Search orders", tool.get("description"));
        verify(clientMapper).updateById(client);
        verify(callLogMapper).insert(any());
    }

    @Test
    void rejectsMcpJsonRpcWithoutValidBearerKey() {
        ControlMcpEndpointController controller = new ControlMcpEndpointController(
                mock(ControlMcpClientMapper.class),
                mock(ControlMcpVisibilityMapper.class),
                mock(ControlMcpCallLogMapper.class),
                mock(RuntimeProxyClient.class));

        ResponseEntity<Map<String, Object>> response = controller.jsonRpc(
                null,
                Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/list"));

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertEquals("invalid_token", response.getBody().get("error"));
    }

    @Test
    void servesMcpToolsCallThroughRuntimeWithoutFallingThroughToRetiredProxy() {
        ControlMcpClientMapper clientMapper = mock(ControlMcpClientMapper.class);
        ControlMcpVisibilityMapper visibilityMapper = mock(ControlMcpVisibilityMapper.class);
        ControlMcpCallLogMapper callLogMapper = mock(ControlMcpCallLogMapper.class);
        RuntimeProxyClient runtimeClient = mock(RuntimeProxyClient.class);
        ControlMcpEndpointController controller = new ControlMcpEndpointController(
                clientMapper,
                visibilityMapper,
                callLogMapper,
                runtimeClient);
        ControlMcpClientEntity client = new ControlMcpClientEntity();
        client.setId(2L);
        client.setName("Codex");
        client.setApiKeyHash("e2540177bcb31c311dd7ee0b0693cc6b8affd0697099b673fdc4c67a4b9390f4");
        client.setEnabled(true);
        client.setToolWhitelistJson("[\"orders.search\"]");
        when(clientMapper.selectOne(any())).thenReturn(client);
        ControlMcpVisibilityEntity visibility = new ControlMcpVisibilityEntity();
        visibility.setTargetKind("TOOL");
        visibility.setTargetName("orders.search");
        visibility.setExposed(true);
        when(visibilityMapper.selectList(any())).thenReturn(List.of(visibility));
        when(runtimeClient.executeRuntimeTool(eq("orders.search"), any())).thenReturn(ResponseEntity.ok(Map.of(
                "success", true,
                "data", Map.of("orderId", "O-1"))));

        ResponseEntity<Map<String, Object>> response = controller.jsonRpc(
                "Bearer mcp_test",
                Map.of(
                        "jsonrpc", "2.0",
                        "id", 7,
                        "method", "tools/call",
                        "params", Map.of(
                                "name", "orders.search",
                                "arguments", Map.of("orderId", "O-1"))));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("2.0", response.getBody().get("jsonrpc"));
        assertEquals(7, response.getBody().get("id"));
        Map<?, ?> result = (Map<?, ?>) response.getBody().get("result");
        assertEquals("orders.search", result.get("name"));
        assertEquals(Map.of("success", true, "data", Map.of("orderId", "O-1")), result.get("output"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> runtimeBody = forClass(Map.class);
        verify(runtimeClient).executeRuntimeTool(eq("orders.search"), runtimeBody.capture());
        assertEquals(Map.of("orderId", "O-1"), runtimeBody.getValue().get("arguments"));
        assertEquals("MCP_TOOLS_CALL", runtimeBody.getValue().get("intentHint"));
        verify(clientMapper).updateById(client);
        verify(callLogMapper).insert(any());
    }

    @Test
    void managesA2aAdminRoutesThroughRuntimeAgentLookupWithoutFallingThroughToRetiredProxy() {
        ControlA2aEndpointMapper endpointMapper = mock(ControlA2aEndpointMapper.class);
        ControlA2aCallLogMapper callLogMapper = mock(ControlA2aCallLogMapper.class);
        RuntimeProxyClient runtimeClient = mock(RuntimeProxyClient.class);
        ControlA2aAdminController controller = new ControlA2aAdminController(endpointMapper, callLogMapper, runtimeClient);
        ControlA2aEndpointEntity endpoint = new ControlA2aEndpointEntity();
        endpoint.setId(4L);
        endpoint.setAgentId("agent-1");
        endpoint.setAgentKey("orders-page-copilot");
        endpoint.setCardJson("{\"name\":\"Orders\"}");
        endpoint.setEnabled(true);
        Page<ControlA2aEndpointEntity> endpoints = new Page<>(1, 20);
        endpoints.setRecords(List.of(endpoint));
        endpoints.setTotal(1);
        when(endpointMapper.selectPage(any(), any())).thenReturn(endpoints);
        when(endpointMapper.selectById(4L)).thenReturn(endpoint);
        when(endpointMapper.selectOne(any())).thenReturn(null);
        when(runtimeClient.getAgent("agent-1")).thenReturn(ResponseEntity.ok(Map.of(
                "id", "agent-1",
                "keySlug", "orders-page-copilot",
                "name", "Orders",
                "description", "Orders copilot",
                "projectId", 7L,
                "projectCode", "orders")));
        Page<ControlA2aCallLogEntity> logs = new Page<>(1, 20);
        logs.setRecords(List.of(new ControlA2aCallLogEntity()));
        logs.setTotal(1);
        when(callLogMapper.selectPage(any(), any())).thenReturn(logs);

        ResponseEntity<Page<ControlA2aEndpointEntity>> listed = controller.listEndpoints(1, 20, "orders", true);
        ResponseEntity<?> detail = controller.getEndpoint(4L);
        ResponseEntity<ControlA2aEndpointEntity> upserted = controller.upsertEndpoint(Map.of(
                "agentId", "agent-1",
                "card", Map.of("version", "1.0.0"),
                "enabled", true));
        ResponseEntity<Void> enabled = controller.setEnabled(4L, false);
        ResponseEntity<Page<ControlA2aCallLogEntity>> callLogs = controller.listLogs(1, 20, "orders", "tasks/send", true);
        ResponseEntity<Void> deleted = controller.delete(4L);

        assertEquals(1, listed.getBody().getTotal());
        assertEquals(HttpStatus.OK, detail.getStatusCode());
        assertEquals("orders-page-copilot", upserted.getBody().getAgentKey());
        assertEquals(HttpStatus.OK, enabled.getStatusCode());
        assertEquals(1, callLogs.getBody().getTotal());
        assertEquals(HttpStatus.OK, deleted.getStatusCode());
        verify(endpointMapper).insert(any());
        verify(endpointMapper).updateById(endpoint);
        verify(endpointMapper).deleteById(4L);
    }

    @Test
    void servesA2aAgentCardAndMessageSendWithoutFallingThroughToRetiredProxy() {
        ControlA2aEndpointMapper endpointMapper = mock(ControlA2aEndpointMapper.class);
        ControlA2aCallLogMapper callLogMapper = mock(ControlA2aCallLogMapper.class);
        RuntimeProxyClient runtimeClient = mock(RuntimeProxyClient.class);
        ControlA2aEndpointController controller = new ControlA2aEndpointController(
                endpointMapper,
                callLogMapper,
                mock(ControlA2aTaskMapper.class),
                runtimeClient);
        ControlA2aEndpointEntity endpoint = new ControlA2aEndpointEntity();
        endpoint.setId(4L);
        endpoint.setAgentId("agent-1");
        endpoint.setAgentKey("orders-page-copilot");
        endpoint.setProjectId(7L);
        endpoint.setProjectCode("orders");
        endpoint.setEnvironment("dev");
        endpoint.setTenantId("tenant-a");
        endpoint.setCardJson("{\"name\":\"Orders\",\"description\":\"Orders agent\"}");
        endpoint.setEnabled(true);
        when(endpointMapper.selectOne(any())).thenReturn(endpoint);
        when(runtimeClient.executeAgent(any())).thenReturn(ResponseEntity.ok(Map.of(
                "answer", "订单已找到",
                "sessionId", "a2a-session-1",
                "metadata", Map.of("traceId", "trace-1"))));

        ResponseEntity<Map<String, Object>> card = controller.agentCard("orders-page-copilot");
        ResponseEntity<Map<String, Object>> message = controller.jsonRpc(
                "orders-page-copilot",
                Map.of(
                        "jsonrpc", "2.0",
                        "id", 1,
                        "method", "message/send",
                        "params", Map.of(
                                "contextId", "ctx-1",
                                "metadata", Map.of("userId", "user-1"),
                                "message", Map.of(
                                        "parts", List.of(Map.of(
                                                "kind", "text",
                                                "text", "查订单"))))));

        assertEquals(HttpStatus.OK, card.getStatusCode());
        assertEquals("Orders", card.getBody().get("name"));
        assertEquals("/a2a/orders-page-copilot/jsonrpc", card.getBody().get("url"));
        assertEquals(HttpStatus.OK, message.getStatusCode());
        assertEquals("2.0", message.getBody().get("jsonrpc"));
        assertEquals(1, message.getBody().get("id"));
        Map<?, ?> task = (Map<?, ?>) message.getBody().get("result");
        assertEquals("task", task.get("kind"));
        assertEquals("ctx-1", task.get("contextId"));
        Map<?, ?> status = (Map<?, ?>) task.get("status");
        assertEquals("completed", status.get("state"));
        Map<?, ?> agentMessage = (Map<?, ?>) status.get("message");
        List<?> parts = (List<?>) agentMessage.get("parts");
        assertTrue(((Map<?, ?>) parts.get(0)).get("text").toString().contains("订单已找到"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> runtimeBody = forClass(Map.class);
        verify(runtimeClient).executeAgent(runtimeBody.capture());
        assertEquals("agent-1", runtimeBody.getValue().get("agentDefinitionId"));
        assertEquals("查订单", runtimeBody.getValue().get("message"));
        assertEquals("A2A_MESSAGE_SEND", runtimeBody.getValue().get("intentHint"));
        verify(callLogMapper, times(2)).insert(any());
    }

    @Test
    void returnsA2aJsonRpcErrorWhenEndpointMissing() {
        ControlA2aEndpointMapper endpointMapper = mock(ControlA2aEndpointMapper.class);
        ControlA2aEndpointController controller = new ControlA2aEndpointController(
                endpointMapper,
                mock(ControlA2aCallLogMapper.class),
                mock(ControlA2aTaskMapper.class),
                mock(RuntimeProxyClient.class));
        when(endpointMapper.selectOne(any())).thenReturn(null);

        ResponseEntity<Map<String, Object>> response = controller.jsonRpc(
                "missing-agent",
                Map.of("jsonrpc", "2.0", "id", "req-1", "method", "message/send"));

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals("2.0", response.getBody().get("jsonrpc"));
        assertEquals("req-1", response.getBody().get("id"));
        Map<?, ?> error = (Map<?, ?>) response.getBody().get("error");
        assertEquals(-32004, error.get("code"));
    }

    @Test
    void servesA2aTaskGetAndCancelWithoutFallingThroughToRetiredProxy() {
        ControlA2aEndpointMapper endpointMapper = mock(ControlA2aEndpointMapper.class);
        ControlA2aCallLogMapper callLogMapper = mock(ControlA2aCallLogMapper.class);
        ControlA2aTaskMapper taskMapper = mock(ControlA2aTaskMapper.class);
        ControlA2aEndpointController controller = new ControlA2aEndpointController(
                endpointMapper,
                callLogMapper,
                taskMapper,
                mock(RuntimeProxyClient.class));
        ControlA2aEndpointEntity endpoint = new ControlA2aEndpointEntity();
        endpoint.setId(4L);
        endpoint.setAgentId("agent-1");
        endpoint.setAgentKey("orders-page-copilot");
        endpoint.setEnabled(true);
        when(endpointMapper.selectOne(any())).thenReturn(endpoint);
        ControlA2aTaskEntity task = new ControlA2aTaskEntity();
        task.setId(9L);
        task.setTaskId("task-1");
        task.setEndpointId(4L);
        task.setAgentKey("orders-page-copilot");
        task.setContextId("ctx-1");
        task.setUserId("user-1");
        task.setState("working");
        task.setTraceId("trace-1");
        when(taskMapper.selectOne(any())).thenReturn(task);

        ResponseEntity<Map<String, Object>> fetched = controller.jsonRpc(
                "orders-page-copilot",
                Map.of("jsonrpc", "2.0", "id", 2, "method", "tasks/get", "params", Map.of("id", "task-1")));
        ResponseEntity<Map<String, Object>> canceled = controller.jsonRpc(
                "orders-page-copilot",
                Map.of("jsonrpc", "2.0", "id", 3, "method", "tasks/cancel", "params", Map.of("id", "task-1")));

        assertEquals(HttpStatus.OK, fetched.getStatusCode());
        Map<?, ?> fetchedTask = (Map<?, ?>) fetched.getBody().get("result");
        assertEquals("task-1", fetchedTask.get("id"));
        assertEquals("working", ((Map<?, ?>) fetchedTask.get("status")).get("state"));
        assertEquals(HttpStatus.OK, canceled.getStatusCode());
        Map<?, ?> canceledTask = (Map<?, ?>) canceled.getBody().get("result");
        assertEquals("task-1", canceledTask.get("id"));
        assertEquals("canceled", ((Map<?, ?>) canceledTask.get("status")).get("state"));
        assertEquals("canceled", task.getState());
        verify(taskMapper).updateById(task);
        verify(callLogMapper, times(2)).insert(any());
    }
}
