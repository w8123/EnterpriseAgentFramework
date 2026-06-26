package com.enterprise.ai.agent.platform.control.mcp;

import com.enterprise.ai.agent.platform.control.acl.ToolAclDecision;
import com.enterprise.ai.agent.platform.control.acl.ToolAclService;
import com.enterprise.ai.agent.platform.control.governance.GuardDecisionLogService;
import com.enterprise.ai.agent.tools.ToolRegistry;
import com.enterprise.ai.runtime.contract.AiTool;
import com.enterprise.ai.runtime.contract.ToolParameter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpServerEndpointToolAclAuditTest {

    @Test
    void jsonrpcToolCallRecordsAuditWhenToolAclDenies() {
        AiTool tool = new FakeTool("delete_order");
        ToolRegistry toolRegistry = new ToolRegistry(List.of(tool));
        McpClientService clientService = mock(McpClientService.class);
        McpVisibilityService visibilityService = mock(McpVisibilityService.class);
        ToolAclService toolAclService = mock(ToolAclService.class);
        GuardDecisionLogService guardDecisionLogService = mock(GuardDecisionLogService.class);
        McpCallLogMapper callLogMapper = mock(McpCallLogMapper.class);

        McpClientEntity client = new McpClientEntity();
        client.setId(7L);
        client.setName("orders-mcp");
        client.setProjectCode("bzsdk");
        client.setTenantId("default");

        when(clientService.authenticate("secret")).thenReturn(Optional.of(client));
        when(clientService.toolWhitelistOf(client)).thenReturn(List.of());
        when(clientService.rolesOf(client)).thenReturn(List.of("auditor"));
        when(visibilityService.isExposed("delete_order")).thenReturn(true);
        when(toolAclService.decide(List.of("auditor"), "delete_order", false, "bzsdk"))
                .thenReturn(ToolAclDecision.DENY_EXPLICIT);

        McpServerEndpoint endpoint = new McpServerEndpoint(
                new ObjectMapper(),
                clientService,
                visibilityService,
                callLogMapper,
                toolRegistry,
                toolAclService,
                guardDecisionLogService);

        endpoint.jsonrpc(Map.of(
                "jsonrpc", "2.0",
                "id", "1",
                "method", "tools/call",
                "params", Map.of("name", "delete_order", "arguments", Map.of())),
                "Bearer secret",
                null,
                null);

        verify(guardDecisionLogService).record(
                any(String.class),
                eq("TOOL_ACL"),
                eq("TOOL"),
                eq("delete_order"),
                eq("DENY"),
                eq("DENY_EXPLICIT"),
                any(Map.class));
    }

    @Test
    void jsonrpcToolCallRecordsAuditWhenClientWhitelistDenies() {
        AiTool tool = new FakeTool("delete_order");
        ToolRegistry toolRegistry = new ToolRegistry(List.of(tool));
        McpClientService clientService = mock(McpClientService.class);
        McpVisibilityService visibilityService = mock(McpVisibilityService.class);
        ToolAclService toolAclService = mock(ToolAclService.class);
        GuardDecisionLogService guardDecisionLogService = mock(GuardDecisionLogService.class);
        McpCallLogMapper callLogMapper = mock(McpCallLogMapper.class);

        McpClientEntity client = new McpClientEntity();
        client.setId(7L);
        client.setName("orders-mcp");
        client.setProjectCode("bzsdk");
        client.setTenantId("default");

        when(clientService.authenticate("secret")).thenReturn(Optional.of(client));
        when(clientService.toolWhitelistOf(client)).thenReturn(List.of("query_order"));
        when(clientService.rolesOf(client)).thenReturn(List.of("auditor"));
        when(visibilityService.isExposed("delete_order")).thenReturn(true);

        McpServerEndpoint endpoint = new McpServerEndpoint(
                new ObjectMapper(),
                clientService,
                visibilityService,
                callLogMapper,
                toolRegistry,
                toolAclService,
                guardDecisionLogService);

        endpoint.jsonrpc(Map.of(
                        "jsonrpc", "2.0",
                        "id", "1",
                        "method", "tools/call",
                        "params", Map.of("name", "delete_order", "arguments", Map.of())),
                "Bearer secret",
                null,
                null);

        verify(guardDecisionLogService).record(
                any(String.class),
                eq("MCP_TOOL_ACCESS"),
                eq("TOOL"),
                eq("delete_order"),
                eq("DENY"),
                eq("CLIENT_WHITELIST"),
                any(Map.class));
    }

    private record FakeTool(String name) implements AiTool {
        @Override
        public String description() {
            return "fake tool";
        }

        @Override
        public List<ToolParameter> parameters() {
            return List.of();
        }

        @Override
        public Object execute(Map<String, Object> args) {
            return Map.of();
        }
    }
}
