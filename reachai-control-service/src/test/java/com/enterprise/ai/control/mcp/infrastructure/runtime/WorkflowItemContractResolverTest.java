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

    @Test
    void ownerRiskFloorCannotBeDowngradedAndUnclassifiedLegacyToolsFailClosed() {
        stubWorkflowLookup();
        Map<String, Object> version = version("ACTIVE", "{\"inputSchemaJson\":\"{}\",\"businessMethodRiskFloor\":\"WRITE\"}");
        version.put("graphSpecSnapshotJson", "{\"nodes\":[{\"type\":\"tool\",\"ref\":{\"assetType\":\"BUSINESS_METHOD\",\"qualifiedName\":\"orders:create\"}}]}");
        stubVersions(version);
        McpToolProjection resolved = resolver.resolve(WORKFLOW_ID);
        assertEquals("WRITE", resolved.minimumRiskLevel());
        assertTrue(resolved.hasTrustedRiskContract());
        assertThrows(com.enterprise.ai.control.mcp.domain.McpDomainException.class,
                () -> new McpToolProjection(resolved.name(), resolved.description(), resolved.inputSchemaJson(),
                        resolved.sourceKind(), resolved.sourceRef(), resolved.workflowVersionId(), "READ", null,
                        resolved.minimumRiskLevel(), resolved.riskContractVersion()));
        version.put("snapshotJson", "{\"inputSchemaJson\":\"{}\"}");
        assertEquals("MCP_WORKFLOW_RISK_UNRESOLVABLE", assertThrows(McpContractResolutionException.class,
                () -> resolver.resolve(WORKFLOW_ID)).code());
    }

    @Test
    void irreversibleFloorAndUnclassifiedSideEffectNodesFailClosed() {
        stubWorkflowLookup();
        Map<String, Object> version = version("ACTIVE", "{\"inputSchemaJson\":\"{}\",\"businessMethodRiskFloor\":\"IRREVERSIBLE\"}");
        version.put("graphSpecSnapshotJson", "{\"nodes\":[{\"type\":\"TOOL\",\"ref\":{\"assetType\":\"BUSINESS_METHOD\",\"qualifiedName\":\"orders:delete\"}}]}");
        stubVersions(version);
        McpToolProjection resolved = resolver.resolve(WORKFLOW_ID);
        assertEquals("IRREVERSIBLE", resolved.minimumRiskLevel());
        assertThrows(com.enterprise.ai.control.mcp.domain.McpDomainException.class,
                () -> new McpToolProjection(resolved.name(), resolved.description(), resolved.inputSchemaJson(),
                        resolved.sourceKind(), resolved.sourceRef(), resolved.workflowVersionId(), "WRITE", null,
                        resolved.minimumRiskLevel(), resolved.riskContractVersion()));
        version.put("graphSpecSnapshotJson", "{\"nodes\":[{\"type\":\"http\"}]}");
        assertEquals("MCP_WORKFLOW_RISK_UNRESOLVABLE", assertThrows(McpContractResolutionException.class,
                () -> resolver.resolve(WORKFLOW_ID)).code());
    }

    @Test
    void httpReadNeedsOwnerClassificationAndWriteRetainsItsFloor() {
        stubWorkflowLookup();
        Map<String, Object> version = version("ACTIVE", "{\"inputSchemaJson\":\"{}\"}");
        version.put("graphSpecSnapshotJson", "{\"nodes\":[{\"type\":\"TOOL\",\"ref\":{\"assetType\":\"HTTP_API\",\"qualifiedName\":\"http-api:12\"}}]}");
        stubVersions(version);
        assertEquals("MCP_WORKFLOW_RISK_UNRESOLVABLE", assertThrows(McpContractResolutionException.class,
                () -> resolver.resolve(WORKFLOW_ID)).code());
        version.put("snapshotJson", "{\"inputSchemaJson\":\"{}\",\"httpApiRiskClassified\":true}");
        assertEquals("READ", resolver.resolve(WORKFLOW_ID).minimumRiskLevel());
        version.put("snapshotJson", "{\"inputSchemaJson\":\"{}\",\"httpApiRiskFloor\":\"WRITE\"}");
        assertEquals("WRITE", resolver.resolve(WORKFLOW_ID).minimumRiskLevel());
    }

    @Test
    void supportedCanvasAliasesAreClassifiedButUnclassifiedIntegrationAliasesAreNot() {
        stubWorkflowLookup();
        Map<String, Object> version = version("ACTIVE", "{\"inputSchemaJson\":\"{}\"}");
        version.put("graphSpecSnapshotJson", "{\"nodes\":[{\"type\":\"condition\"},{\"type\":\"variable\"},{\"type\":\"knowledge\"}]}");
        stubVersions(version);
        assertEquals("READ", resolver.resolve(WORKFLOW_ID).minimumRiskLevel());
        version.put("graphSpecSnapshotJson", "{\"nodes\":[{\"type\":\"mcp\"}]}");
        assertEquals("MCP_WORKFLOW_RISK_UNRESOLVABLE", assertThrows(McpContractResolutionException.class,
                () -> resolver.resolve(WORKFLOW_ID)).code());
    }

    @Test
    void mixedGraphUsesHighestOwnerFloorEvenWhenHttpReferenceUsesName() {
        stubWorkflowLookup();
        Map<String, Object> version = version("ACTIVE", "{\"inputSchemaJson\":\"{}\",\"businessMethodRiskFloor\":\"READ\",\"httpApiRiskFloor\":\"WRITE\"}");
        version.put("graphSpecSnapshotJson", "{\"nodes\":[{\"type\":\"TOOL\",\"ref\":{\"assetType\":\"BUSINESS_METHOD\",\"qualifiedName\":\"erp:read\"}},{\"type\":\"tool\",\"ref\":{\"assetType\":\"HTTP_API\",\"name\":\"http-api:12\"}}]}");
        stubVersions(version);
        assertEquals("WRITE", resolver.resolve(WORKFLOW_ID).minimumRiskLevel());
    }

    @Test
    void subWorkflowMergesThePinnedChildRiskUpward() {
        stubWorkflowLookup();
        Map<String, Object> parent = version("ACTIVE", "{\"inputSchemaJson\":\"{}\"}");
        parent.put("graphSpecSnapshotJson", "{\"nodes\":[{\"type\":\"LLM\"},{\"type\":\"SUB_WORKFLOW\",\"config\":{\"workflowId\":\"wf-child\",\"versionId\":7}}]}");
        stubVersions(parent);
        Map<String, Object> child = new LinkedHashMap<>();
        child.put("id", 7L);
        child.put("status", "RETIRED");
        child.put("snapshotJson", "{}");
        child.put("graphSpecSnapshotJson", "{\"nodes\":[{\"type\":\"KNOWLEDGE_WRITE\"}]}");
        when(runtimeProxyClient.listWorkflowVersions("wf-child")).thenReturn(ResponseEntity.ok(List.of(child)));
        assertEquals("WRITE", resolver.resolve(WORKFLOW_ID).minimumRiskLevel());

        child.put("graphSpecSnapshotJson", "{\"nodes\":[{\"type\":\"CODE\"}]}");
        assertEquals("READ", resolver.resolve(WORKFLOW_ID).minimumRiskLevel());

        child.put("status", "DRAFT");
        assertEquals("MCP_WORKFLOW_RISK_UNRESOLVABLE", assertThrows(McpContractResolutionException.class,
                () -> resolver.resolve(WORKFLOW_ID)).code());

        parent.put("graphSpecSnapshotJson", "{\"nodes\":[{\"type\":\"SUB_WORKFLOW\",\"config\":{\"workflowId\":\"wf-child\"}}]}");
        assertEquals("MCP_WORKFLOW_RISK_UNRESOLVABLE", assertThrows(McpContractResolutionException.class,
                () -> resolver.resolve(WORKFLOW_ID)).code());
    }

    @Test
    void conflictingSubWorkflowPinsCannotPublishWithLowerRisk() {
        stubWorkflowLookup();
        Map<String, Object> parent = version("ACTIVE", "{\"inputSchemaJson\":\"{}\"}");
        parent.put("graphSpecSnapshotJson", "{\"nodes\":[{\"type\":\"SUB_WORKFLOW\",\"config\":{" +
                "\"workflowId\":\"write-child\",\"versionId\":8," +
                "\"subWorkflowConfig\":{\"workflowId\":\"read-child\",\"versionId\":7}}}]}");
        stubVersions(parent);
        assertEquals("MCP_WORKFLOW_RISK_UNRESOLVABLE", assertThrows(McpContractResolutionException.class,
                () -> resolver.resolve(WORKFLOW_ID)).code());
    }

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
        body.put("graphSpecSnapshotJson", "{\"nodes\":[]}");
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
        Map<String, Object> version = version("ACTIVE", snapshotJson);
        version.put("graphSpecSnapshotJson", "{\"nodes\":[],\"inputSchema\":{\"type\":\"object\",\"properties\":{\"region\":{\"type\":\"string\"}}}}");
        stubVersions(version);

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
        assertEquals("READ", projection.riskLevel());
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
