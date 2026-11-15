package com.enterprise.ai.runtime.internal;

import com.enterprise.ai.common.capability.HttpApiConsoleContracts;
import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDefinitionView;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowManagementService;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowVersionView;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RuntimeHttpApiFlowReuseTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String REF = "http-api:project:dev:" + "a".repeat(64);
    private final RuntimeCapabilityCatalogClient catalog = mock(RuntimeCapabilityCatalogClient.class);
    private final RuntimeWorkflowManagementService workflows = mock(RuntimeWorkflowManagementService.class);
    private final RuntimeHttpApiFlowService service = new RuntimeHttpApiFlowService(catalog, workflows, null);

    private HttpApiConsoleContracts.ExecutionContext owner(boolean supported) throws Exception {
        return new HttpApiConsoleContracts.ExecutionContext(1, 41L, REF, 7L, "project", "dev", "POST", "/orders",
                true, "ACCEPTED", null, "b".repeat(64), "b".repeat(64), "rev",
                JSON.readTree("{\"sideEffect\":\"WRITE\",\"parameters\":[]}"),
                false, "GET only", supported, supported ? null : "unsupported contract");
    }

    private RuntimeWorkflowDefinitionView workflow(HttpApiConsoleContracts.ExecutionContext owner, String graph) {
        return RuntimeWorkflowDefinitionView.builder().id("wf").projectId(7L).projectCode("project")
                .keySlug("api-project-41").name("orders").status("ACTIVE").graphSpecJson(graph)
                .extraJson("{\"source\":\"HTTP_API_FLOW\",\"httpApiAssetId\":41,\"sourceRef\":\"" + REF + "\"}").build();
    }

    private String graph(HttpApiConsoleContracts.ExecutionContext owner, boolean pinned, long id) throws Exception {
        ObjectNode graph = JSON.valueToTree(service.graphSpec(owner, "orders"));
        ObjectNode tool = (ObjectNode) graph.path("nodes").get(1);
        ((ObjectNode) tool.path("config")).put("httpApiAssetId", id);
        if (pinned) {
            ObjectNode ref = (ObjectNode) tool.path("ref");
            ref.put("assetType", "HTTP_API"); ref.put("assetId", id); ref.put("definitionId", 8);
        }
        return JSON.writeValueAsString(graph);
    }

    private RuntimeHttpApiFlowService.FlowResult generate() {
        return service.generate(new RuntimeHttpApiFlowService.BatchRequest(7L, "project",
                List.of(new RuntimeHttpApiFlowService.ApiItem(41L, "orders", null)), true, "user")).get(0);
    }

    @Test
    void acceptsWriteOwnerContractAndReusesVerifiedActiveVersion() throws Exception {
        var owner = owner(true);
        when(catalog.getHttpApiExecutionContext(41L, "project")).thenReturn(owner);
        when(workflows.findByKeySlug("api-project-41")).thenReturn(Optional.of(workflow(owner, graph(owner, false, 41))));
        when(workflows.listVersions("wf")).thenReturn(List.of(RuntimeWorkflowVersionView.builder()
                .status("ACTIVE").graphSpecSnapshotJson(graph(owner, true, 41)).build()));
        assertNull(generate().error());
        verify(workflows, never()).publish(anyString(), anyString(), anyInt(), anyString(), anyString(), anyString());
    }

    @Test
    void rejectsUnsupportedContractAndActiveVersionForDifferentApi() throws Exception {
        var owner = owner(false);
        when(catalog.getHttpApiExecutionContext(41L, "project")).thenReturn(owner);
        assertEquals("unsupported contract", generate().error());
        owner = owner(true);
        when(catalog.getHttpApiExecutionContext(41L, "project")).thenReturn(owner);
        when(workflows.findByKeySlug("api-project-41")).thenReturn(Optional.of(workflow(owner, graph(owner, false, 41))));
        when(workflows.listVersions("wf")).thenReturn(List.of(RuntimeWorkflowVersionView.builder()
                .status("ACTIVE").graphSpecSnapshotJson(graph(owner, true, 42)).build()));
        assertTrue(generate().error().contains("活动版本"));
    }

    @Test
    void rejectsDraftForDifferentApiEvenIfSlugMatches() throws Exception {
        var owner = owner(true);
        when(catalog.getHttpApiExecutionContext(41L, "project")).thenReturn(owner);
        when(workflows.findByKeySlug("api-project-41")).thenReturn(Optional.of(workflow(owner, graph(owner, false, 42))));
        assertTrue(generate().error().contains("来源或草稿"));
        verify(workflows, never()).listVersions(anyString());
    }

    @Test
    void reusesMatchingLegacyTruncatedSlug() throws Exception {
        String code = "longproject".repeat(9);
        String legacySlug = ("api-" + code + "-41").substring(0, 64);
        String newSlug = RuntimeHttpApiFlowService.keySlug(code, 41);
        var owner = new HttpApiConsoleContracts.ExecutionContext(1, 41L, REF, 7L, code, "dev", "POST", "/orders",
                true, "ACCEPTED", null, "b".repeat(64), "b".repeat(64), "rev",
                JSON.readTree("{\"sideEffect\":\"WRITE\",\"parameters\":[]}"), false, "GET only", true, null);
        var old = RuntimeWorkflowDefinitionView.builder().id("old").projectId(7L).projectCode(code)
                .keySlug(legacySlug).name("orders").status("DRAFT").graphSpecJson(graph(owner, false, 41))
                .extraJson("{\"source\":\"HTTP_API_FLOW\",\"httpApiAssetId\":41,\"sourceRef\":\"" + REF + "\"}").build();
        when(catalog.getHttpApiExecutionContext(41L, code)).thenReturn(owner);
        when(workflows.findByKeySlug(newSlug)).thenReturn(Optional.empty());
        when(workflows.findByKeySlug(legacySlug)).thenReturn(Optional.of(old));
        var result = service.generate(new RuntimeHttpApiFlowService.BatchRequest(7L, code,
                List.of(new RuntimeHttpApiFlowService.ApiItem(41L, "orders", null)), false, "user")).get(0);
        assertNull(result.error());
        assertEquals("old", result.workflowId());
        assertFalse(result.created());
    }
}
