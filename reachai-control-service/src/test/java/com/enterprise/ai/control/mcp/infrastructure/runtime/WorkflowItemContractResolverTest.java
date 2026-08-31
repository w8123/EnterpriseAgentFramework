package com.enterprise.ai.control.mcp.infrastructure.runtime;

import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import com.enterprise.ai.control.mcp.application.port.McpContractResolutionException;
import com.enterprise.ai.control.mcp.domain.publication.McpPublicationItemKind;
import com.enterprise.ai.control.mcp.domain.publication.McpToolProjection;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkflowItemContractResolverTest {

    private static final String WORKFLOW_ID = "wf-order-sync";

    private RuntimeProxyClient runtimeProxyClient;
    private WorkflowItemContractResolver resolver;

    @BeforeEach
    void setUp() {
        runtimeProxyClient = mock(RuntimeProxyClient.class);
        resolver = new WorkflowItemContractResolver(runtimeProxyClient, new ObjectMapper());
    }

    private Map<String, Object> workflow() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", WORKFLOW_ID);
        body.put("keySlug", "order-sync");
        body.put("name", "订单同步");
        body.put("description", "同步订单到下游系统");
        body.put("status", "ACTIVE");
        return body;
    }

    private Map<String, Object> version(String status, String snapshotJson) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", 91L);
        body.put("workflowId", WORKFLOW_ID);
        body.put("version", "v1.2.0");
        body.put("status", status);
        body.put("snapshotJson", snapshotJson);
        body.put("graphSpecSnapshotJson", null);
        return body;
    }

    private void stubWorkflowLookup() {
        when(runtimeProxyClient.getWorkflow(WORKFLOW_ID))
                .thenReturn(ResponseEntity.ok(workflow()));
    }

    private void stubVersions(Map<String, Object>... versions) {
        when(runtimeProxyClient.listWorkflowVersions(WORKFLOW_ID))
                .thenReturn(ResponseEntity.ok(List.of(versions)));
    }

    @Test
    void supportedKindIsWorkflow() {
        assertEquals(McpPublicationItemKind.WORKFLOW, resolver.supportedKind());
    }

    @Test
    void activeVersionSnapshotSchemaWinsAndVersionIdIsPinned() {
        stubWorkflowLookup();
        String snapshotJson = """
                {"inputSchemaJson":"{\\"type\\":\\"object\\",\\"properties\\":{\\"orderId\\":{\\"type\\":\\"string\\"}},\\"required\\":[\\"orderId\\"]}","riskLevel":"WRITE","graphSpec":"{}"}
                """;
        stubVersions(version("ACTIVE", snapshotJson));

        McpToolProjection projection = resolver.resolve(WORKFLOW_ID);

        assertEquals("order-sync", projection.name());
        assertEquals("同步订单到下游系统", projection.description());
        assertEquals(91L, projection.workflowVersionId());
        assertEquals(McpPublicationItemKind.WORKFLOW, projection.sourceKind());
        assertEquals(WORKFLOW_ID, projection.sourceRef());
        assertEquals("WRITE", projection.riskLevel());
        assertEquals("{\"type\":\"object\",\"properties\":{\"orderId\":{\"type\":\"string\"}},\"required\":[\"orderId\"]}",
                projection.inputSchemaJson());
    }

    @Test
    void graphSpecSnapshotSchemaIsUsedWhenSnapshotHasNoExplicitSchema() {
        stubWorkflowLookup();
        Map<String, Object> body = workflow();
        body.put("description", null);
        body.put("name", "订单同步");
        when(runtimeProxyClient.getWorkflow(WORKFLOW_ID)).thenReturn(ResponseEntity.ok(body));
        String snapshotJson = """
                {"graphSpec":"{\\"inputSchema\\":{\\"type\\":\\"object\\",\\"properties\\":{\\"region\\":{\\"type\\":\\"string\\"}}}}"}
                """;
        stubVersions(version("ACTIVE", snapshotJson));

        McpToolProjection projection = resolver.resolve(WORKFLOW_ID);

        assertEquals("{\"type\":\"object\",\"properties\":{\"region\":{\"type\":\"string\"}}}",
                projection.inputSchemaJson());
        assertEquals("订单同步", projection.description());
    }

    @Test
    void workingCopyExplicitSchemaFallsBackWhenVersionSnapshotHasNoSchema() {
        Map<String, Object> body = workflow();
        body.put("inputSchemaJson", "{\"type\":\"object\",\"properties\":{\"force\":{\"type\":\"boolean\"}}}");
        when(runtimeProxyClient.getWorkflow(WORKFLOW_ID)).thenReturn(ResponseEntity.ok(body));
        stubVersions(version("ACTIVE", "{}"));

        McpToolProjection projection = resolver.resolve(WORKFLOW_ID);

        assertEquals("{\"type\":\"object\",\"properties\":{\"force\":{\"type\":\"boolean\"}}}",
                projection.inputSchemaJson());
        assertEquals("UNKNOWN", projection.riskLevel());
    }

    @Test
    void retiredVersionsOnlyFailsWithWorkflowIdInMessage() {
        stubWorkflowLookup();
        stubVersions(version("RETIRED", "{\"inputSchemaJson\":\"{}\"}"));

        McpContractResolutionException exception = assertThrows(McpContractResolutionException.class,
                () -> resolver.resolve(WORKFLOW_ID));
        assertEquals("MCP_WORKFLOW_VERSION_NOT_ACTIVE", exception.code());
        assertTrue(exception.getMessage().contains(WORKFLOW_ID));
    }

    @Test
    void emptyVersionListFailsWithWorkflowIdInMessage() {
        stubWorkflowLookup();
        when(runtimeProxyClient.listWorkflowVersions(WORKFLOW_ID))
                .thenReturn(ResponseEntity.ok(List.of()));

        McpContractResolutionException exception = assertThrows(McpContractResolutionException.class,
                () -> resolver.resolve(WORKFLOW_ID));
        assertEquals("MCP_WORKFLOW_VERSION_NOT_ACTIVE", exception.code());
        assertTrue(exception.getMessage().contains(WORKFLOW_ID));
    }

    @Test
    void workflowNotFoundFailsResolution() {
        feign.Request request = feign.Request.create(
                feign.Request.HttpMethod.GET,
                "/api/workflows/" + WORKFLOW_ID,
                Map.of(),
                null,
                java.nio.charset.StandardCharsets.UTF_8,
                null);
        when(runtimeProxyClient.getWorkflow(WORKFLOW_ID))
                .thenThrow(new feign.FeignException.NotFound("not found", request, null, Map.of()));

        McpContractResolutionException exception = assertThrows(McpContractResolutionException.class,
                () -> resolver.resolve(WORKFLOW_ID));
        assertEquals("MCP_WORKFLOW_NOT_FOUND", exception.code());
        assertTrue(exception.getMessage().contains(WORKFLOW_ID));
    }

    @Test
    void noResolvableSchemaAnywhereFailsWithWorkflowIdInMessage() {
        stubWorkflowLookup();
        stubVersions(version("ACTIVE", "{}"));

        McpContractResolutionException exception = assertThrows(McpContractResolutionException.class,
                () -> resolver.resolve(WORKFLOW_ID));
        assertEquals("MCP_WORKFLOW_SCHEMA_UNRESOLVABLE", exception.code());
        assertTrue(exception.getMessage().contains(WORKFLOW_ID));
    }

    @Test
    void missingKeySlugFallsBackToWorkflowId() {
        Map<String, Object> body = workflow();
        body.put("keySlug", null);
        when(runtimeProxyClient.getWorkflow(WORKFLOW_ID)).thenReturn(ResponseEntity.ok(body));
        stubVersions(version("ACTIVE", "{\"inputSchemaJson\":\"{}\"}"));

        assertEquals(WORKFLOW_ID, resolver.resolve(WORKFLOW_ID).name());
    }

    @Test
    void blankSourceRefFailsResolution() {
        McpContractResolutionException exception = assertThrows(McpContractResolutionException.class,
                () -> resolver.resolve(" "));
        assertEquals("MCP_REQUIRED_FIELD", exception.code());
    }
}
