package com.enterprise.ai.control.aiassist;

import com.enterprise.ai.control.client.capability.CapabilityProjectOnboardingClient;
import com.enterprise.ai.control.client.model.ControlModelCatalogClient;
import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ControlAiCodingProjectControllerTest {

    @Test
    void exposesAiCodingGatewayManifestWithoutEchoingRawProjectKey() {
        CapabilityProjectOnboardingClient client = mock(CapabilityProjectOnboardingClient.class);
        RuntimeProxyClient runtimeClient = mock(RuntimeProxyClient.class);
        ControlAiCodingProjectController controller = new ControlAiCodingProjectController(client, runtimeClient,
                mock(ControlModelCatalogClient.class));
        when(client.getOnboardingProjectById(7L)).thenReturn(Map.of(
                "id", 7L,
                "name", "Orders",
                "projectCode", "orders",
                "projectKind", "REGISTERED",
                "environment", "dev",
                "aiCodingAccess", Map.of("enabled", true, "accessKey", "aic_secret")
        ));
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET",
                "/api/ai-coding/projects/7/manifest");
        request.setScheme("http");
        request.setServerName("localhost");
        request.setServerPort(18603);

        ResponseEntity<ControlAiCodingProjectController.AiCodingGatewayManifest> response =
                controller.manifest(7L, request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("reachai.ai-coding.gateway.v1", response.getBody().schema());
        assertEquals(7L, response.getBody().project().id());
        assertEquals("X-ReachAI-AiCoding-Key", response.getBody().auth().headerName());
        assertEquals("http://localhost:18603/api/ai-coding/projects/7/manifest",
                response.getBody().endpoints().manifestUrl());
        assertEquals("http://localhost:18603/api/ai-coding/projects/7/page-assistant/onboarding-manifest",
                response.getBody().endpoints().pageAssistantManifestUrl());
        assertEquals("com.enterprise.ai:reachai-spring-boot2-starter:1.0.0-SNAPSHOT",
                response.getBody().sdkArtifacts().get(1).coordinates());
        assertEquals("npm-or-local-sdk-build",
                response.getBody().sdkArtifacts().get(2).sourcePolicy());
        assertEquals("ApiResult", response.getBody().responseShapes().get("embed").wrapper());
        assertEquals("data.answer", response.getBody().responseShapes().get("embed").fields().get("answer"));
        assertEquals("bare-json", response.getBody().responseShapes().get("aiAccessSessions").wrapper());
        assertEquals("sessionId", response.getBody().responseShapes().get("aiAccessSessions").fields().get("sessionId"));
        assertFalse(response.toString().contains("aic_secret"));
        verify(client).getOnboardingProjectById(7L);
    }

    @Test
    void exposesExternalPageAssistantManifestUnderAiCodingGatewayPath() {
        CapabilityProjectOnboardingClient client = mock(CapabilityProjectOnboardingClient.class);
        RuntimeProxyClient runtimeClient = mock(RuntimeProxyClient.class);
        ControlAiCodingProjectController controller = new ControlAiCodingProjectController(client, runtimeClient,
                mock(ControlModelCatalogClient.class));
        when(client.getOnboardingProjectById(7L)).thenReturn(Map.of(
                "id", 7L,
                "name", "Orders",
                "projectCode", "orders",
                "projectKind", "REGISTERED",
                "environment", "dev",
                "registryCredentialConfigured", true,
                "aiCodingAccess", Map.of("enabled", true, "accessKey", "aic_secret")
        ));
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET",
                "/api/ai-coding/projects/7/page-assistant/onboarding-manifest");
        request.setScheme("http");
        request.setServerName("localhost");
        request.setServerPort(18603);

        ResponseEntity<Map<String, Object>> response =
                controller.pageAssistantManifest(7L, "orders.list", "/orders", request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("reachai.page-assistant.onboarding.v1", response.getBody().get("schema"));
        assertFalse(response.toString().contains("aic_secret"));
        verify(client).getOnboardingProjectById(7L);
    }

    @Test
    void exposesExternalSdkAccessSessionRoutesWithoutFallingThroughToRetiredProxy() {
        CapabilityProjectOnboardingClient client = mock(CapabilityProjectOnboardingClient.class);
        RuntimeProxyClient runtimeClient = mock(RuntimeProxyClient.class);
        ControlAiCodingProjectController controller = new ControlAiCodingProjectController(client, runtimeClient,
                mock(ControlModelCatalogClient.class));
        when(client.getOnboardingProjectById(7L)).thenReturn(Map.of(
                "id", 7L,
                "name", "Orders",
                "projectCode", "orders",
                "baseUrl", "https://orders.example.com",
                "contextPath", "/orders-api",
                "registryCredentialConfigured", true,
                "aiCodingAccess", Map.of("enabled", true, "accessKey", "aic_secret")
        ));

        ResponseEntity<ControlAiAssistProjectController.AiAccessSessionView> start =
                controller.startAccessSession(7L, "Codex");
        ResponseEntity<ControlAiAssistProjectController.AiAccessSessionView> latest =
                controller.latestAccessSession(7L);
        ResponseEntity<ControlAiAssistProjectController.AiAccessSessionView> report =
                controller.reportAccessSessionStep(7L, "sdk-access-7", "backend-starter", Map.of(
                        "status", "PASS",
                        "message", "starter wired"
                ));
        ResponseEntity<ControlAiAssistProjectController.AiAccessCheckRunResponse> checks =
                controller.runAccessSessionChecks(7L, "sdk-access-7", Map.of());

        assertEquals(HttpStatus.OK, start.getStatusCode());
        assertEquals("SDK_ACCESS", start.getBody().scenario());
        assertEquals("sdk-access-7", latest.getBody().sessionId());
        assertEquals("PASS", report.getBody().status());
        assertEquals(1, report.getBody().completedSteps());
        assertEquals("PASS", checks.getBody().checkResult().overallStatus());
        assertEquals(
                "https://orders.example.com/orders-api/reachai/registry/capabilities/sync",
                checks.getBody().checkResult().checks().stream()
                        .filter(check -> "SDK_SYNC_CALLBACK".equals(check.key()))
                        .findFirst()
                        .orElseThrow()
                        .evidence());
    }

    @Test
    void provisionsPageCopilotAgentThroughRuntimeService() {
        CapabilityProjectOnboardingClient client = mock(CapabilityProjectOnboardingClient.class);
        RuntimeProxyClient runtimeClient = mock(RuntimeProxyClient.class);
        ControlModelCatalogClient modelClient = mock(ControlModelCatalogClient.class);
        ControlAiCodingProjectController controller = new ControlAiCodingProjectController(client, runtimeClient,
                modelClient);
        when(client.getOnboardingProjectById(7L)).thenReturn(Map.of(
                "id", 7L,
                "name", "Orders",
                "projectCode", "orders",
                "projectKind", "REGISTERED"
        ));
        when(runtimeClient.listAgents(7L, "orders"))
                .thenReturn(ResponseEntity.ok(List.of()));
        when(runtimeClient.createAgent(org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn(ResponseEntity.ok(Map.of(
                        "id", "agent-1",
                        "keySlug", "orders-page-copilot",
                        "name", "Orders Page Copilot",
                        "projectCode", "orders",
                        "enabled", true
                )));
        when(modelClient.list(null, "LLM", null)).thenReturn(ResponseEntity.ok(Map.of(
                "code", 200,
                "data", List.of(Map.of(
                        "id", "model-1",
                        "modelType", "LLM",
                        "status", "ACTIVE")))));
        when(runtimeClient.listAgentConfigVersions("agent-1"))
                .thenReturn(ResponseEntity.ok(List.of()));
        when(runtimeClient.saveAgentConfigDraft(org.mockito.ArgumentMatchers.eq("agent-1"),
                org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn(ResponseEntity.ok(Map.of(
                        "id", 21L,
                        "agentId", "agent-1",
                        "versionNo", 1,
                        "runtimeType", "AGENTSCOPE",
                        "modelInstanceId", "model-1",
                        "status", "DRAFT",
                        "tools", List.of()
                )));
        when(runtimeClient.publishAgentConfigVersion(org.mockito.ArgumentMatchers.eq("agent-1"),
                org.mockito.ArgumentMatchers.eq(21L),
                org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn(ResponseEntity.ok(Map.of(
                        "id", 21L,
                        "agentId", "agent-1",
                        "versionNo", 1,
                        "runtimeType", "AGENTSCOPE",
                        "modelInstanceId", "model-1",
                        "status", "ACTIVE",
                        "tools", List.of()
                )));

        ResponseEntity<Map<String, Object>> response =
                controller.provisionProjectAgent(7L, Map.of("requestedBy", "Codex"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("agent-provisioning.v2", response.getBody().get("schema"));
        assertEquals(true, response.getBody().get("createdAgent"));
        assertEquals(true, response.getBody().get("createdSupervisorConfig"));
        assertEquals("ACTIVE", ((Map<?, ?>) response.getBody().get("supervisorConfig")).get("status"));
        assertEquals(21L, ((Map<?, ?>) response.getBody().get("agent")).get("activeConfigVersionId"));
        verify(runtimeClient).createAgent(org.mockito.ArgumentMatchers.anyMap());
        ArgumentCaptor<Map<String, Object>> agentBodyCaptor = ArgumentCaptor.forClass(Map.class);
        verify(runtimeClient).createAgent(agentBodyCaptor.capture());
        assertFalse(agentBodyCaptor.getValue().containsKey("systemPrompt"));
        assertFalse(agentBodyCaptor.getValue().containsKey("modelInstanceId"));
        assertFalse(agentBodyCaptor.getValue().containsKey("entryConfigJson"));
        ArgumentCaptor<Map<String, Object>> configBodyCaptor = ArgumentCaptor.forClass(Map.class);
        verify(runtimeClient).saveAgentConfigDraft(org.mockito.ArgumentMatchers.eq("agent-1"), configBodyCaptor.capture());
        assertEquals("model-1", configBodyCaptor.getValue().get("modelInstanceId"));
        verify(runtimeClient, never()).createWorkflow(org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    void exposesExternalPageAssistantSessionRoutesWithoutFallingThroughToRetiredProxy() {
        CapabilityProjectOnboardingClient client = mock(CapabilityProjectOnboardingClient.class);
        RuntimeProxyClient runtimeClient = mock(RuntimeProxyClient.class);
        ControlAiCodingProjectController controller = new ControlAiCodingProjectController(client, runtimeClient,
                mock(ControlModelCatalogClient.class));
        when(client.getOnboardingProjectById(7L)).thenReturn(Map.of(
                "id", 7L,
                "name", "Orders",
                "projectCode", "orders",
                "registryCredentialConfigured", true,
                "aiCodingAccess", Map.of("enabled", true, "accessKey", "aic_secret")
        ));

        ResponseEntity<ControlAiAssistProjectController.AiAccessSessionView> start =
                controller.startPageAssistantSession(7L, new ControlAiAssistProjectController.PageAssistantSessionRequest(
                        "Codex",
                        "orders.list",
                        "/orders",
                        List.of("orders.refresh")));
        ResponseEntity<ControlAiAssistProjectController.AiAccessSessionView> report =
                controller.reportPageAssistantSessionStep(7L, "page-assistant-7-orders.list", "catalog-sync", Map.of(
                        "status", "PASS",
                        "message", "catalog synced"
                ));
        ResponseEntity<ControlAiAssistProjectController.PageAssistantCheckRunResponse> checks =
                controller.runPageAssistantChecks(7L, "page-assistant-7-orders.list", Map.of(
                        "pageKey", "orders.list",
                        "routePattern", "/orders"
                ));

        assertEquals(HttpStatus.OK, start.getStatusCode());
        assertEquals("PAGE_ASSISTANT", start.getBody().scenario());
        assertEquals("orders.list", start.getBody().targetPageKey());
        assertEquals("PASS", report.getBody().status());
        assertEquals("PASS", checks.getBody().checkResult().overallStatus());
    }

    @Test
    void acceptsExternalContextCandidateSubmissionsWithoutFallingThroughToRetiredProxy() {
        CapabilityProjectOnboardingClient client = mock(CapabilityProjectOnboardingClient.class);
        RuntimeProxyClient runtimeClient = mock(RuntimeProxyClient.class);
        ControlAiCodingProjectController controller = new ControlAiCodingProjectController(client, runtimeClient,
                mock(ControlModelCatalogClient.class));
        when(client.getOnboardingProjectById(7L)).thenReturn(Map.of(
                "id", 7L,
                "name", "Orders",
                "projectCode", "orders"
        ));

        ResponseEntity<Map<String, Object>> single = controller.createContextCandidate(7L, Map.of(
                "content", "Order cancel API requires audit reason",
                "candidateType", "NOTE"
        ));
        ResponseEntity<List<Map<String, Object>>> batch = controller.createContextCandidateBatch(7L, List.of(
                Map.of("content", "Order refund workflow should ask for approval"),
                Map.of("content", "Order export action is read-only")
        ));
        ResponseEntity<List<Map<String, Object>>> listed = controller.listContextCandidates(
                7L,
                "ai-coding-submission-1",
                "PENDING");

        assertEquals(HttpStatus.OK, single.getStatusCode());
        assertEquals("PENDING", single.getBody().get("status"));
        assertEquals("orders", single.getBody().get("projectCode"));
        assertEquals(2, batch.getBody().size());
        assertEquals(List.of(), listed.getBody());
    }
}
