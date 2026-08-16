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
                mock(ControlProjectAgentProvisioningService.class));
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
        assertEquals("reachai.ai-coding.gateway.v3", response.getBody().schema());
        assertEquals(7L, response.getBody().project().id());
        assertEquals("X-ReachAI-AiCoding-Key", response.getBody().auth().headerName());
        assertEquals("http://localhost:18603/api/ai-coding/projects/7/manifest",
                response.getBody().endpoints().manifestUrl());
        assertEquals(
                "http://localhost:18603/api/ai-coding/handoffs/{handoffId}/activate",
                response.getBody().endpoints().handoffActivationUrlTemplate());
        assertEquals(
                "http://localhost:18603/api/ai-coding/tasks/{taskId}/context",
                response.getBody().endpoints().taskContextUrlTemplate());
        assertEquals(
                "http://localhost:18603/api/ai-coding/tasks/{taskId}/events",
                response.getBody().endpoints().taskEventsUrlTemplate());
        assertEquals(
                "http://localhost:18603/api/ai-coding/tasks/{taskId}/artifacts",
                response.getBody().endpoints().taskArtifactsUrlTemplate());
        assertEquals(
                "http://localhost:18603/api/workflows/{workflowId}/ai-coding/resource-bindings",
                response.getBody().endpoints().workflowResourceBindingsUrlTemplate());
        assertEquals("com.enterprise.ai:reachai-spring-boot2-starter:1.0.0-SNAPSHOT",
                response.getBody().sdkArtifacts().get(1).coordinates());
        assertEquals("platform-artifact-tarball",
                response.getBody().sdkArtifacts().get(2).sourcePolicy());
        assertNotNull(response.getBody().sdkArtifacts().get(2).integritySha256());
        assertTrue(response.getBody().sdkArtifacts().get(2).installCommand()
                .contains("scripts/install-embed-chat.mjs"));
        assertEquals("business-frontend-package-root",
                response.getBody().sdkArtifacts().get(2).installWorkingDirectory());
        assertEquals(6, response.getBody().gatewayChecklist().size());
        assertEquals("ApiResult", response.getBody().responseShapes().get("embed").wrapper());
        assertEquals("data.answer", response.getBody().responseShapes().get("embed").fields().get("answer"));
        assertFalse(response.getBody().responseShapes().containsKey("aiAccessSessions"));
        assertFalse(response.getBody().responseShapes().containsKey("gatewayChecklist"));
        ResponseEntity<ControlAiAssistProjectController.OnboardingManifestResponse>
                onboarding = controller.onboardingManifest(7L, request);
        assertNotNull(onboarding.getBody());
        assertTrue(onboarding.getBody().agentSupervisor()
                .requiredSteps().stream()
                .anyMatch(step -> step.contains("replaceWorkflowId")));
        assertFalse(response.toString().contains("aic_secret"));
        verify(client, org.mockito.Mockito.times(2)).getOnboardingProjectById(7L);
    }

    @Test
    void attachForwardsRuntimeAiCodingErrorBodyInsteadOfEmptyNotFound() {
        CapabilityProjectOnboardingClient client = mock(CapabilityProjectOnboardingClient.class);
        RuntimeProxyClient runtimeClient = mock(RuntimeProxyClient.class);
        ControlAiCodingProjectController controller = new ControlAiCodingProjectController(client, runtimeClient,
                mock(ControlProjectAgentProvisioningService.class));
        when(client.getOnboardingProjectById(7L)).thenReturn(Map.of("id", 7L, "projectCode", "orders"));
        feign.Request feignRequest = feign.Request.create(
                feign.Request.HttpMethod.POST,
                "/internal/runtime/projects/7/agent-supervisor/workflow-tools/attach",
                Map.of(),
                null,
                java.nio.charset.StandardCharsets.UTF_8,
                null);
        byte[] body = """
                {"schema":"ai-coding-error.v1","code":"WORKFLOW_NOT_FOUND","message":"workflow not found: missing","details":{},"requestId":"abc123"}
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        when(runtimeClient.attachAgentSupervisorWorkflowTool(org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.anyMap()))
                .thenThrow(new feign.FeignException.NotFound("not found", feignRequest, body, Map.of()));

        ResponseEntity<Object> response = controller.attachAgentSupervisorWorkflowTool(7L, Map.of("workflowId", "missing"));

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) response.getBody();
        assertEquals("ai-coding-error.v1", error.get("schema"));
        assertEquals("WORKFLOW_NOT_FOUND", error.get("code"));
    }

    @Test
    void attachDistinguishesCapabilityProjectNotFoundFromRuntimeWorkflowNotFound() {
        CapabilityProjectOnboardingClient client = mock(CapabilityProjectOnboardingClient.class);
        RuntimeProxyClient runtimeClient = mock(RuntimeProxyClient.class);
        ControlAiCodingProjectController controller = new ControlAiCodingProjectController(client, runtimeClient,
                mock(ControlProjectAgentProvisioningService.class));
        feign.Request feignRequest = feign.Request.create(
                feign.Request.HttpMethod.GET,
                "/internal/capability/projects/by-id/99",
                Map.of(),
                null,
                java.nio.charset.StandardCharsets.UTF_8,
                null);
        when(client.getOnboardingProjectById(99L))
                .thenThrow(new feign.FeignException.NotFound("missing project", feignRequest, null, Map.of()));

        ResponseEntity<Object> response = controller.attachAgentSupervisorWorkflowTool(99L, Map.of("workflowId", "wf-1"));

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) response.getBody();
        assertEquals("ai-coding-error.v1", error.get("schema"));
        assertEquals("CAPABILITY_PROJECT_NOT_FOUND", error.get("code"));
        verify(runtimeClient, never()).attachAgentSupervisorWorkflowTool(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    void provisionsPageCopilotAgentThroughRuntimeService() {
        CapabilityProjectOnboardingClient client = mock(CapabilityProjectOnboardingClient.class);
        RuntimeProxyClient runtimeClient = mock(RuntimeProxyClient.class);
        ControlModelCatalogClient modelClient = mock(ControlModelCatalogClient.class);
        ControlProjectAgentProvisioningService provisioningService =
                new ControlProjectAgentProvisioningService(
                        client,
                        runtimeClient,
                        modelClient);
        ControlAiCodingProjectController controller = new ControlAiCodingProjectController(client, runtimeClient,
                provisioningService);
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
        assertEquals("Orders 页面副驾驶 Agent", agentBodyCaptor.getValue().get("name"));
        assertEquals(
                ControlProjectAgentProvisioningService.DEFAULT_PAGE_COPILOT_DESCRIPTION,
                agentBodyCaptor.getValue().get("description"));
        assertFalse(agentBodyCaptor.getValue().containsKey("systemPrompt"));
        assertFalse(agentBodyCaptor.getValue().containsKey("modelInstanceId"));
        assertFalse(agentBodyCaptor.getValue().containsKey("entryConfigJson"));
        ArgumentCaptor<Map<String, Object>> configBodyCaptor = ArgumentCaptor.forClass(Map.class);
        verify(runtimeClient).saveAgentConfigDraft(org.mockito.ArgumentMatchers.eq("agent-1"), configBodyCaptor.capture());
        assertEquals("model-1", configBodyCaptor.getValue().get("modelInstanceId"));
        assertEquals(
                ControlProjectAgentProvisioningService.DEFAULT_PAGE_COPILOT_SYSTEM_PROMPT,
                configBodyCaptor.getValue().get("systemPrompt"));
        verify(runtimeClient, never()).createWorkflow(org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    void upgradesOnlyLegacyManagedPageCopilotContentToChinese() {
        CapabilityProjectOnboardingClient client = mock(CapabilityProjectOnboardingClient.class);
        RuntimeProxyClient runtimeClient = mock(RuntimeProxyClient.class);
        ControlModelCatalogClient modelClient = mock(ControlModelCatalogClient.class);
        ControlProjectAgentProvisioningService provisioningService =
                new ControlProjectAgentProvisioningService(client, runtimeClient, modelClient);
        when(client.getOnboardingProjectById(7L)).thenReturn(Map.of(
                "id", 7L,
                "name", "班组建设16",
                "projectCode", "bzjs16"));
        when(runtimeClient.listAgents(7L, "bzjs16")).thenReturn(ResponseEntity.ok(List.of(Map.of(
                "id", "agent-1",
                "keySlug", "bzjs16-page-copilot",
                "name", "班组建设16 Page Copilot",
                "description", "Project page copilot Agent for embedded chat and Workflow routing."))));
        when(runtimeClient.updateAgent(
                org.mockito.ArgumentMatchers.eq("agent-1"),
                org.mockito.ArgumentMatchers.anyMap())).thenReturn(ResponseEntity.ok(Map.of(
                        "id", "agent-1",
                        "keySlug", "bzjs16-page-copilot",
                        "name", "班组建设16 页面副驾驶 Agent",
                        "description", ControlProjectAgentProvisioningService.DEFAULT_PAGE_COPILOT_DESCRIPTION)));
        when(modelClient.list(null, "LLM", null)).thenReturn(ResponseEntity.ok(Map.of(
                "data", List.of(Map.of(
                        "id", "model-1",
                        "modelType", "LLM",
                        "status", "ACTIVE")))));
        when(runtimeClient.listAgentConfigVersions("agent-1")).thenReturn(ResponseEntity.ok(List.of(Map.of(
                "id", 20L,
                "agentId", "agent-1",
                "versionNo", 2,
                "status", "ACTIVE",
                "modelInstanceId", "model-1",
                "systemPrompt", "You are the project's page copilot Supervisor. Understand the request, plan, and select one or more permitted Workflows as tools. Use page-action Workflows only when the user explicitly asks to open, navigate, query, or operate a page.",
                "tools", List.of()))));
        when(runtimeClient.saveAgentConfigDraft(
                org.mockito.ArgumentMatchers.eq("agent-1"),
                org.mockito.ArgumentMatchers.anyMap())).thenReturn(ResponseEntity.ok(Map.of(
                        "id", 21L,
                        "status", "DRAFT")));
        when(runtimeClient.publishAgentConfigVersion(
                org.mockito.ArgumentMatchers.eq("agent-1"),
                org.mockito.ArgumentMatchers.eq(21L),
                org.mockito.ArgumentMatchers.anyMap())).thenReturn(ResponseEntity.ok(Map.of(
                        "id", 21L,
                        "versionNo", 3,
                        "status", "ACTIVE",
                        "systemPrompt", ControlProjectAgentProvisioningService.DEFAULT_PAGE_COPILOT_SYSTEM_PROMPT,
                        "tools", List.of())));

        Map<String, Object> result = provisioningService.provision(
                7L,
                Map.of("requestedBy", "Codex"));

        assertEquals(false, result.get("createdAgent"));
        assertEquals(true, result.get("createdSupervisorConfig"));
        ArgumentCaptor<Map<String, Object>> agentUpdateCaptor = ArgumentCaptor.forClass(Map.class);
        verify(runtimeClient).updateAgent(
                org.mockito.ArgumentMatchers.eq("agent-1"),
                agentUpdateCaptor.capture());
        assertEquals("班组建设16 页面副驾驶 Agent", agentUpdateCaptor.getValue().get("name"));
        assertEquals(
                ControlProjectAgentProvisioningService.DEFAULT_PAGE_COPILOT_DESCRIPTION,
                agentUpdateCaptor.getValue().get("description"));
        ArgumentCaptor<Map<String, Object>> configBodyCaptor = ArgumentCaptor.forClass(Map.class);
        verify(runtimeClient).saveAgentConfigDraft(
                org.mockito.ArgumentMatchers.eq("agent-1"),
                configBodyCaptor.capture());
        assertEquals(
                ControlProjectAgentProvisioningService.DEFAULT_PAGE_COPILOT_SYSTEM_PROMPT,
                configBodyCaptor.getValue().get("systemPrompt"));
        verify(runtimeClient, never()).createAgent(org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    void preservesCustomizedPageCopilotContent() {
        CapabilityProjectOnboardingClient client = mock(CapabilityProjectOnboardingClient.class);
        RuntimeProxyClient runtimeClient = mock(RuntimeProxyClient.class);
        ControlModelCatalogClient modelClient = mock(ControlModelCatalogClient.class);
        ControlProjectAgentProvisioningService provisioningService =
                new ControlProjectAgentProvisioningService(client, runtimeClient, modelClient);
        when(client.getOnboardingProjectById(7L)).thenReturn(Map.of(
                "id", 7L,
                "name", "班组建设16",
                "projectCode", "bzjs16"));
        when(runtimeClient.listAgents(7L, "bzjs16")).thenReturn(ResponseEntity.ok(List.of(Map.of(
                "id", "agent-1",
                "keySlug", "bzjs16-page-copilot",
                "name", "班组建设知识助手",
                "description", "仅处理班组档案查询。"))));
        when(modelClient.list(null, "LLM", null)).thenReturn(ResponseEntity.ok(Map.of(
                "data", List.of(Map.of(
                        "id", "model-1",
                        "modelType", "LLM",
                        "status", "ACTIVE")))));
        when(runtimeClient.listAgentConfigVersions("agent-1")).thenReturn(ResponseEntity.ok(List.of(Map.of(
                "id", 20L,
                "status", "ACTIVE",
                "systemPrompt", "你是班组档案查询助手。",
                "tools", List.of()))));

        Map<String, Object> result = provisioningService.provision(7L, Map.of());

        assertEquals(false, result.get("createdAgent"));
        assertEquals(false, result.get("createdSupervisorConfig"));
        verify(runtimeClient, never()).updateAgent(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyMap());
        verify(runtimeClient, never()).saveAgentConfigDraft(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyMap());
        verify(runtimeClient, never()).publishAgentConfigVersion(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    void acceptsExternalContextCandidateSubmissionsWithoutFallingThroughToRetiredProxy() {
        CapabilityProjectOnboardingClient client = mock(CapabilityProjectOnboardingClient.class);
        RuntimeProxyClient runtimeClient = mock(RuntimeProxyClient.class);
        ControlAiCodingProjectController controller = new ControlAiCodingProjectController(client, runtimeClient,
                mock(ControlProjectAgentProvisioningService.class));
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
