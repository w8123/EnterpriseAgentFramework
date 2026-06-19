package com.enterprise.ai.agent.controller;

import com.enterprise.ai.agent.workflow.AgentEntryEntity;
import com.enterprise.ai.agent.workflow.AgentEntryService;
import com.enterprise.ai.agent.assist.AiAccessSessionService;
import com.enterprise.ai.agent.identity.PageActionCatalogContracts;
import com.enterprise.ai.agent.identity.PageActionCatalogService;
import com.enterprise.ai.agent.identity.PageCatalogRegisterResult;
import com.enterprise.ai.agent.registry.RegistryCredentialEntity;
import com.enterprise.ai.agent.registry.RegistrySecurityService;
import com.enterprise.ai.agent.registry.SdkAccessCheckService;
import com.enterprise.ai.agent.scan.ScanProjectEntity;
import com.enterprise.ai.agent.scan.ScanProjectService;
import com.enterprise.ai.agent.workflow.AgentProvisioningService;
import com.enterprise.ai.agent.workflow.AgentWorkflowBindingEntity;
import com.enterprise.ai.agent.workflow.PageAssistantWorkflowBindingResult;
import com.enterprise.ai.agent.workflow.PageAssistantWorkflowBindingService;
import com.enterprise.ai.agent.workflow.WorkflowDefinitionEntity;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipInputStream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiAssistControllerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ScanProjectService scanProjectService = mock(ScanProjectService.class);
    private final RegistrySecurityService registrySecurityService = mock(RegistrySecurityService.class);
    private final AgentEntryService agentEntryService = mock(AgentEntryService.class);
    private final AgentProvisioningService agentProvisioningService = mock(AgentProvisioningService.class);
    private final AiAccessSessionService accessSessionService = mock(AiAccessSessionService.class);
    private final PageActionCatalogService pageActionCatalogService = mock(PageActionCatalogService.class);
    private final PageAssistantWorkflowBindingService pageAssistantWorkflowBindingService = mock(PageAssistantWorkflowBindingService.class);
    private final AiAssistController controller = new AiAssistController(
            scanProjectService,
            registrySecurityService,
            agentEntryService,
            agentProvisioningService,
            accessSessionService,
            pageActionCatalogService,
            pageAssistantWorkflowBindingService);

    @BeforeEach
    void setUpAgentProvisioningDefaults() {
        when(agentProvisioningService.pageCopilotKeySlug(anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0, String.class) + "-page-copilot");
    }

    @Test
    void onboardingManifestIncludesAppKeyButNeverAppSecret() {
        ScanProjectEntity project = project();
        RegistryCredentialEntity credential = new RegistryCredentialEntity();
        credential.setAppKey("demo-app-key");
        credential.setAppSecret("secret-must-not-leak");
        when(scanProjectService.getById(1L)).thenReturn(project);
        when(registrySecurityService.findPrimaryActiveCredential("demo-service"))
                .thenReturn(Optional.of(credential));

        ResponseEntity<?> response = controller.onboardingManifest(1L, null, request());
        Map<String, Object> body = objectMapper.convertValue(response.getBody(), new TypeReference<>() {
        });
        Map<String, Object> projectBody = objectMapper.convertValue(body.get("project"), new TypeReference<>() {
        });
        Map<String, Object> securityBody = objectMapper.convertValue(body.get("security"), new TypeReference<>() {
        });
        String serialized = objectMapper.convertValue(body, Map.class).toString();

        assertEquals("reachai.onboarding.v1", body.get("schema"));
        assertEquals("demo-app-key", projectBody.get("registryAppKey"));
        assertEquals(Boolean.TRUE, projectBody.get("registryCredentialConfigured"));
        assertEquals("REACHAI_REGISTRY_APP_SECRET", securityBody.get("appSecretEnv"));
        assertFalse(serialized.contains("secret-must-not-leak"));
    }

    @Test
    void onboardingManifestIncludesEmbedAgentContract() {
        ScanProjectEntity project = project();
        when(scanProjectService.getById(1L)).thenReturn(project);
        when(registrySecurityService.findPrimaryActiveCredential("demo-service"))
                .thenReturn(Optional.empty());
        when(agentEntryService.list(1L, null, null)).thenReturn(List.of(embedEntry(
                "agent-001",
                "demo-service-assistant",
                "Demo Assistant",
                "demo-service",
                true)));

        ResponseEntity<?> response = controller.onboardingManifest(1L, null, request());
        Map<String, Object> body = objectMapper.convertValue(response.getBody(), new TypeReference<>() {
        });
        Map<String, Object> embedBody = objectMapper.convertValue(body.get("embed"), new TypeReference<>() {
        });

        assertNotNull(embedBody);
        assertEquals("/api/reachai/embed-token", embedBody.get("tokenPath"));
        assertEquals("agent-001", embedBody.get("defaultAgentId"));
        assertEquals("demo-service-assistant", embedBody.get("defaultAgentKeySlug"));
        assertTrue(embedBody.containsKey("allowedAgents"));
    }

    @Test
    void onboardingManifestIncludesAgentProvisioningContract() {
        ScanProjectEntity project = project();
        project.setAiCodingAccessEnabled(true);
        project.setAiCodingAccessKey("rac_valid");
        when(scanProjectService.matchesAiCodingAccessKey(1L, "rac_valid")).thenReturn(true);
        when(scanProjectService.getById(1L)).thenReturn(project);
        when(registrySecurityService.findPrimaryActiveCredential("demo-service"))
                .thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.onboardingManifest(1L, "rac_valid", request());
        Map<String, Object> body = objectMapper.convertValue(response.getBody(), new TypeReference<>() {
        });
        Map<String, Object> provisioningBody = objectMapper.convertValue(body.get("agentProvisioning"), new TypeReference<>() {
        });
        Map<String, Object> agentWorkflowBody = objectMapper.convertValue(body.get("agentWorkflow"), new TypeReference<>() {
        });

        assertEquals("agent-provisioning.v1", provisioningBody.get("model"));
        assertEquals("PAGE_COPILOT", provisioningBody.get("defaultAgentKind"));
        assertEquals("demo-service-page-copilot", provisioningBody.get("defaultKeySlug"));
        assertTrue(provisioningBody.get("provisionAgentUrl").toString()
                .contains("/api/ai-assist/projects/1/agents/provision?aiCodingKey=rac_valid"));
        assertEquals("demo-service-page-copilot", agentWorkflowBody.get("globalAgentKeySlug"));
        assertEquals("PAGE_COPILOT", agentWorkflowBody.get("globalAgentKind"));
        Map<String, Object> workflowAiCodingBody = objectMapper.convertValue(
                agentWorkflowBody.get("workflowAiCoding"), new TypeReference<>() {
                });
        assertTrue(workflowAiCodingBody.get("skillPackageUrl").toString()
                .endsWith("/api/ai-assist/skills/workflow-ai-coding/latest.zip"));
        assertTrue(workflowAiCodingBody.get("createUrl").toString()
                .endsWith("/api/workflows/ai-coding/workflows"));
    }

    @Test
    void provisionProjectAgentAcceptsValidAiCodingKeyAndReturnsAgentWorkflowBinding() {
        ScanProjectEntity project = project();
        AgentEntryEntity agent = embedEntry("agent-1", "demo-service-page-copilot", "Demo Page Copilot", "demo-service", true);
        agent.setAgentKind("PAGE_COPILOT");
        WorkflowDefinitionEntity workflow = new WorkflowDefinitionEntity();
        workflow.setId("workflow-1");
        workflow.setKeySlug("demo-service-page-copilot-default");
        workflow.setName("Demo Page Copilot Default");
        workflow.setWorkflowType("PAGE_COPILOT_DEFAULT");
        workflow.setStatus("DRAFT");
        workflow.setManagedBy("AGENT_PROVISIONING");
        AgentWorkflowBindingEntity binding = new AgentWorkflowBindingEntity();
        binding.setId(9L);
        binding.setAgentId("agent-1");
        binding.setWorkflowId("workflow-1");
        binding.setBindingType("DEFAULT");
        binding.setEnabled(true);
        when(scanProjectService.matchesAiCodingAccessKey(1L, "rac_valid")).thenReturn(true);
        when(scanProjectService.getById(1L)).thenReturn(project);
        when(agentProvisioningService.provisionPageCopilot(project, "Cursor", true))
                .thenReturn(new AgentProvisioningService.AgentProvisioningResult(
                        agent,
                        workflow,
                        binding,
                        true,
                        true,
                        true));

        ResponseEntity<?> response = controller.provisionProjectAgent(
                1L,
                "rac_valid",
                new AiAssistController.AgentProvisionRequest("PAGE_COPILOT", true, "Cursor"));
        Map<String, Object> body = objectMapper.convertValue(response.getBody(), new TypeReference<>() {
        });
        Map<String, Object> agentBody = objectMapper.convertValue(body.get("agent"), new TypeReference<>() {
        });
        Map<String, Object> workflowBody = objectMapper.convertValue(body.get("defaultWorkflow"), new TypeReference<>() {
        });
        Map<String, Object> bindingBody = objectMapper.convertValue(body.get("defaultBinding"), new TypeReference<>() {
        });

        assertEquals(200, response.getStatusCode().value());
        assertEquals("agent-provisioning.v1", body.get("schema"));
        assertEquals("demo-service-page-copilot", agentBody.get("keySlug"));
        assertEquals("PAGE_COPILOT", agentBody.get("agentKind"));
        assertEquals("demo-service-page-copilot-default", workflowBody.get("keySlug"));
        assertEquals("DEFAULT", bindingBody.get("bindingType"));
        assertEquals(Boolean.TRUE, body.get("createdAgent"));
    }

    @Test
    void onboardingManifestPrefersCredentialAllowedAgentList() {
        ScanProjectEntity project = project();
        RegistryCredentialEntity credential = new RegistryCredentialEntity();
        credential.setAppKey("demo-app-key");
        credential.setAllowedAgentIdsJson("[\"allowed-assistant\"]");
        when(scanProjectService.getById(1L)).thenReturn(project);
        when(registrySecurityService.findPrimaryActiveCredential("demo-service"))
                .thenReturn(Optional.of(credential));
        when(agentEntryService.findById("allowed-assistant")).thenReturn(Optional.empty());
        when(agentEntryService.findByKeySlug("allowed-assistant")).thenReturn(Optional.of(embedEntry(
                "agent-allowed",
                "allowed-assistant",
                "Allowed Assistant",
                "demo-service",
                true)));

        ResponseEntity<?> response = controller.onboardingManifest(1L, null, request());
        Map<String, Object> body = objectMapper.convertValue(response.getBody(), new TypeReference<>() {
        });
        Map<String, Object> embedBody = objectMapper.convertValue(body.get("embed"), new TypeReference<>() {
        });

        assertEquals("agent-allowed", embedBody.get("defaultAgentId"));
        assertEquals("allowed-assistant", embedBody.get("defaultAgentKeySlug"));
    }

    @Test
    void onboardingManifestRejectsInvalidAiCodingAccessKey() {
        when(scanProjectService.matchesAiCodingAccessKey(1L, "wrong-key")).thenReturn(false);

        ResponseEntity<?> response = controller.onboardingManifest(1L, "wrong-key", request());

        assertEquals(403, response.getStatusCode().value());
    }

    @Test
    void onboardingManifestAcceptsValidAiCodingAccessKey() {
        ScanProjectEntity project = project();
        project.setAiCodingAccessEnabled(true);
        project.setAiCodingAccessKey("rac_valid");
        when(scanProjectService.matchesAiCodingAccessKey(1L, "rac_valid")).thenReturn(true);
        when(scanProjectService.getById(1L)).thenReturn(project);
        when(registrySecurityService.findPrimaryActiveCredential("demo-service")).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.onboardingManifest(1L, "rac_valid", request());

        assertEquals(200, response.getStatusCode().value());
    }

    @Test
    void pageAssistantManifestIncludesCredentialsSessionAndTargetPageButNeverSecret() {
        ScanProjectEntity project = project();
        RegistryCredentialEntity credential = new RegistryCredentialEntity();
        credential.setAppKey("bzjs3");
        credential.setAppSecret("secret-must-not-leak");
        AiAccessSessionService.AccessSessionView session = pageAssistantSession();
        when(scanProjectService.getById(1L)).thenReturn(project);
        when(registrySecurityService.findPrimaryActiveCredential("demo-service"))
                .thenReturn(Optional.of(credential));
        when(accessSessionService.getOrCreatePageAssistantLatest(
                1L,
                new AiAccessSessionService.PageAssistantSessionRequest(
                        "Cursor",
                        "teamArchive.list",
                        "/teams/archive",
                        List.of("getPageState", "search"))))
                .thenReturn(session);

        ResponseEntity<?> response = controller.pageAssistantManifest(
                1L,
                "teamArchive.list",
                "/teams/archive",
                List.of("getPageState", "search"),
                "Cursor",
                null,
                request());
        Map<String, Object> body = objectMapper.convertValue(response.getBody(), new TypeReference<>() {
        });
        Map<String, Object> projectBody = objectMapper.convertValue(body.get("project"), new TypeReference<>() {
        });
        Map<String, Object> securityBody = objectMapper.convertValue(body.get("security"), new TypeReference<>() {
        });
        Map<String, Object> endpointsBody = objectMapper.convertValue(body.get("endpoints"), new TypeReference<>() {
        });
        String serialized = objectMapper.convertValue(body, Map.class).toString();

        assertEquals("reachai.page-assistant-onboarding.v2", body.get("schema"));
        assertEquals("bzjs3", projectBody.get("registryAppKey"));
        assertEquals("REACHAI_REGISTRY_APP_SECRET", securityBody.get("appSecretEnv"));
        assertTrue(endpointsBody.get("stepReportUrl").toString().contains("/page-assistant/sessions/rai_page/steps/{stepKey}/report"));
        assertTrue(endpointsBody.get("checksRunUrl").toString().contains("/page-assistant/sessions/rai_page/checks/run"));
        assertFalse(serialized.contains("secret-must-not-leak"));
    }

    @Test
    void pageAssistantManifestIncludesV2ContractAndRegisterPageEndpoint() {
        ScanProjectEntity project = project();
        project.setAiCodingAccessEnabled(true);
        project.setAiCodingAccessKey("rac_valid");
        AiAccessSessionService.AccessSessionView session = pageAssistantSession();
        when(scanProjectService.matchesAiCodingAccessKey(1L, "rac_valid")).thenReturn(true);
        when(scanProjectService.getById(1L)).thenReturn(project);
        when(registrySecurityService.findPrimaryActiveCredential("demo-service"))
                .thenReturn(Optional.empty());
        when(accessSessionService.getOrCreatePageAssistantLatest(
                1L,
                new AiAccessSessionService.PageAssistantSessionRequest(
                        "Cursor",
                        null,
                        null,
                        null)))
                .thenReturn(session);

        ResponseEntity<?> response = controller.pageAssistantManifest(
                1L,
                null,
                null,
                null,
                "Cursor",
                "rac_valid",
                request());
        Map<String, Object> body = objectMapper.convertValue(response.getBody(), new TypeReference<>() {
        });
        Map<String, Object> endpointsBody = objectMapper.convertValue(body.get("endpoints"), new TypeReference<>() {
        });
        Map<String, Object> contractBody = objectMapper.convertValue(body.get("pageActionContract"), new TypeReference<>() {
        });
        Map<String, Object> localExecutionBody = objectMapper.convertValue(body.get("localExecution"), new TypeReference<>() {
        });
        Map<String, Object> scaffoldBody = objectMapper.convertValue(body.get("scaffold"), new TypeReference<>() {
        });

        assertEquals("reachai.page-assistant-onboarding.v2", body.get("schema"));
        assertEquals(Boolean.TRUE, localExecutionBody.get("requiresLocalShell"));
        assertEquals("__REACHAI_PAGE_BRIDGE__", contractBody.get("bridgeGlobal"));
        assertTrue(contractBody.get("recommendedActions").toString().contains("readTable"));
        Map<String, Object> bridgeApiBody = objectMapper.convertValue(contractBody.get("bridgeApi"), new TypeReference<>() {
        });
        assertEquals("window.__REACHAI_PAGE_BRIDGE__", bridgeApiBody.get("global"));
        assertTrue(bridgeApiBody.get("methods").toString().contains("execute"));
        assertTrue(bridgeApiBody.get("errorCodes").toString().contains("PENDING_CONFIRM"));
        assertEquals("angular", scaffoldBody.get("framework"));
        assertEquals("scripts/reachai-page-assistant.ps1", scaffoldBody.get("helperScriptPath"));
        assertTrue(scaffoldBody.get("scriptDownloadUrl").toString().contains("/scripts/reachai-page-assistant.ps1"));
        assertTrue(scaffoldBody.get("skillPackageUrl").toString().contains("/latest.zip"));
        assertTrue(scaffoldBody.get("scaffoldCommand").toString().contains("reachai-page-assistant.ps1 scaffold"));
        assertTrue(scaffoldBody.get("verifyCommand").toString().contains("reachai-page-assistant.ps1 verify"));
        assertTrue(endpointsBody.get("skillPackageUrl").toString().contains("/latest.zip"));
        assertTrue(endpointsBody.get("scriptDownloadUrl").toString().contains("/scripts/reachai-page-assistant.ps1"));
        assertTrue(endpointsBody.get("registerPageUrl").toString()
                .contains("/page-assistant/pages/register?aiCodingKey=rac_valid"));
        assertTrue(endpointsBody.containsKey("targetBindUrl"));
        assertTrue(endpointsBody.containsKey("catalogSyncUrl"));
        assertTrue(endpointsBody.containsKey("checksRunUrl"));
    }

    @Test
    void updateAiCodingAccessDelegatesAndReturnsSavedKey() {
        ScanProjectEntity updated = project();
        updated.setAiCodingAccessEnabled(true);
        updated.setAiCodingAccessKey("rac_saved");
        when(scanProjectService.updateAiCodingAccess(
                1L,
                new ScanProjectService.AiCodingAccessUpdate(true, "rac_saved")))
                .thenReturn(updated);

        ResponseEntity<?> response = controller.updateAiCodingAccess(
                1L,
                new AiAssistController.AiCodingAccessUpdateRequest(true, "rac_saved"));
        Map<String, Object> body = objectMapper.convertValue(response.getBody(), new TypeReference<>() {
        });

        assertEquals(Boolean.TRUE, body.get("enabled"));
        assertEquals("rac_saved", body.get("accessKey"));
        verify(scanProjectService).updateAiCodingAccess(
                1L,
                new ScanProjectService.AiCodingAccessUpdate(true, "rac_saved"));
    }

    @Test
    void accessSessionReportRejectsInvalidAiCodingAccessKey() {
        when(scanProjectService.matchesAiCodingAccessKey(1L, "wrong-key")).thenReturn(false);

        ResponseEntity<?> response = controller.reportAccessSessionStep(
                1L,
                "rai_demo",
                "gateway-whitelist",
                "wrong-key",
                new AiAccessSessionService.StepReportRequest(
                        "PASS",
                        "done",
                        List.of("gateway/SecurityConfiguration.java"),
                        Map.of("command", "mvn test"),
                        "cursor"));

        assertEquals(403, response.getStatusCode().value());
    }

    @Test
    void accessSessionReportAcceptsValidAiCodingAccessKey() {
        AiAccessSessionService.AccessSessionView view = accessSession();
        when(scanProjectService.matchesAiCodingAccessKey(1L, "rac_valid")).thenReturn(true);
        when(accessSessionService.reportStep(
                1L,
                "rai_demo",
                "gateway-whitelist",
                new AiAccessSessionService.StepReportRequest(
                        "PASS",
                        "done",
                        List.of("gateway/SecurityConfiguration.java"),
                        Map.of("command", "mvn test"),
                        "cursor")))
                .thenReturn(view);

        ResponseEntity<?> response = controller.reportAccessSessionStep(
                1L,
                "rai_demo",
                "gateway-whitelist",
                "rac_valid",
                new AiAccessSessionService.StepReportRequest(
                        "PASS",
                        "done",
                        List.of("gateway/SecurityConfiguration.java"),
                        Map.of("command", "mvn test"),
                        "cursor"));

        assertEquals(200, response.getStatusCode().value());
        Map<String, Object> body = objectMapper.convertValue(response.getBody(), new TypeReference<>() {
        });
        assertEquals("rai_demo", body.get("sessionId"));
    }

    @Test
    void accessSessionCheckDelegatesToSessionService() {
        AiAccessSessionService.AccessCheckRunResponse runResponse = new AiAccessSessionService.AccessCheckRunResponse(
                new SdkAccessCheckService.SdkAccessCheckResponse(
                        1L,
                        "demo-service",
                        SdkAccessCheckService.CheckStatus.PASS,
                        List.of()),
                accessSession());
        SdkAccessCheckService.SdkAccessCheckRequest request = new SdkAccessCheckService.SdkAccessCheckRequest(
                10L,
                Map.of(),
                "http://localhost:8080",
                "/api/reachai/embed-token");
        when(accessSessionService.runChecks(1L, "rai_demo", request)).thenReturn(runResponse);

        ResponseEntity<?> response = controller.runAccessSessionChecks(1L, "rai_demo", request);

        assertEquals(200, response.getStatusCode().value());
        Map<String, Object> body = objectMapper.convertValue(response.getBody(), new TypeReference<>() {
        });
        assertTrue(body.containsKey("checkResult"));
        assertTrue(body.containsKey("session"));
    }

    @Test
    void pageAssistantStepReportAcceptsValidAiCodingAccessKey() {
        when(scanProjectService.matchesAiCodingAccessKey(1L, "rac_valid")).thenReturn(true);
        when(accessSessionService.reportStep(
                1L,
                "rai_page",
                "frontend-handler",
                new AiAccessSessionService.StepReportRequest(
                        "PASS",
                        "handlers registered",
                        List.of("src/pages/team-archive/index.vue"),
                        Map.of("actions", List.of("getPageState", "search")),
                        "cursor")))
                .thenReturn(pageAssistantSession());

        ResponseEntity<?> response = controller.reportPageAssistantSessionStep(
                1L,
                "rai_page",
                "frontend-handler",
                "rac_valid",
                new AiAccessSessionService.StepReportRequest(
                        "PASS",
                        "handlers registered",
                        List.of("src/pages/team-archive/index.vue"),
                        Map.of("actions", List.of("getPageState", "search")),
                        "cursor"));

        assertEquals(200, response.getStatusCode().value());
    }

    @Test
    void pageAssistantWorkflowAiCodingResultRejectsInvalidAiCodingAccessKey() {
        when(scanProjectService.matchesAiCodingAccessKey(1L, "wrong-key")).thenReturn(false);

        ResponseEntity<?> response = controller.reportPageAssistantWorkflowAiCodingResult(
                1L,
                "rai_page",
                "wrong-key",
                new AiAccessSessionService.WorkflowAiCodingResultRequest(
                        "wf-123",
                        "demo-page-assistant",
                        "Demo Page Assistant",
                        "PASS",
                        "done",
                        Map.of("overallStatus", "PASS"),
                        Map.of("overallStatus", "PASS"),
                        Map.of(),
                        "/workflows/wf-123/studio"));

        assertEquals(403, response.getStatusCode().value());
    }

    @Test
    void pageAssistantWorkflowAiCodingResultAcceptsValidAiCodingAccessKey() {
        when(scanProjectService.matchesAiCodingAccessKey(1L, "rac_valid")).thenReturn(true);
        when(accessSessionService.reportWorkflowAiCodingResult(
                1L,
                "rai_page",
                new AiAccessSessionService.WorkflowAiCodingResultRequest(
                        "wf-123",
                        "demo-page-assistant",
                        "Demo Page Assistant",
                        "PASS",
                        "done",
                        Map.of("overallStatus", "PASS"),
                        Map.of("overallStatus", "PASS"),
                        Map.of(),
                        "/workflows/wf-123/studio")))
                .thenReturn(pageAssistantSession());

        ResponseEntity<?> response = controller.reportPageAssistantWorkflowAiCodingResult(
                1L,
                "rai_page",
                "rac_valid",
                new AiAccessSessionService.WorkflowAiCodingResultRequest(
                        "wf-123",
                        "demo-page-assistant",
                        "Demo Page Assistant",
                        "PASS",
                        "done",
                        Map.of("overallStatus", "PASS"),
                        Map.of("overallStatus", "PASS"),
                        Map.of(),
                        "/workflows/wf-123/studio"));

        assertEquals(200, response.getStatusCode().value());
        verify(accessSessionService).reportWorkflowAiCodingResult(
                1L,
                "rai_page",
                new AiAccessSessionService.WorkflowAiCodingResultRequest(
                        "wf-123",
                        "demo-page-assistant",
                        "Demo Page Assistant",
                        "PASS",
                        "done",
                        Map.of("overallStatus", "PASS"),
                        Map.of("overallStatus", "PASS"),
                        Map.of(),
                        "/workflows/wf-123/studio"));
    }

    @Test
    void pageAssistantChecksAcceptValidAiCodingAccessKey() {
        AiAccessSessionService.PageAssistantCheckRunResponse runResponse =
                new AiAccessSessionService.PageAssistantCheckRunResponse(
                        new AiAccessSessionService.PageAssistantCheckResponse(
                                1L,
                                "demo-service",
                                "teamArchive.list",
                                "/teams/archive",
                                "PASS",
                                List.of()),
                        pageAssistantSession());
        AiAccessSessionService.PageAssistantCheckRequest request =
                new AiAccessSessionService.PageAssistantCheckRequest(
                        "teamArchive.list",
                        "/teams/archive",
                        List.of("getPageState", "search"),
                        null,
                        null);
        when(scanProjectService.matchesAiCodingAccessKey(1L, "rac_valid")).thenReturn(true);
        when(accessSessionService.runPageAssistantChecks(1L, "rai_page", request)).thenReturn(runResponse);

        ResponseEntity<?> response = controller.runPageAssistantSessionChecks(
                1L,
                "rai_page",
                "rac_valid",
                request);

        assertEquals(200, response.getStatusCode().value());
        Map<String, Object> body = objectMapper.convertValue(response.getBody(), new TypeReference<>() {
        });
        assertTrue(body.containsKey("checkResult"));
        assertTrue(body.containsKey("session"));
    }

    @Test
    void pageAssistantSessionListAcceptsValidAiCodingAccessKey() {
        AiAccessSessionService.PageAssistantSessionSummaryView summary =
                new AiAccessSessionService.PageAssistantSessionSummaryView(
                        "rai_page",
                        1L,
                        "demo-service",
                        "Cursor",
                        "teamArchive.list",
                        "/team-build/depart-management",
                        "RUNNING",
                        "IN_PROGRESS",
                        8,
                        4,
                        0,
                        6,
                        "handlers registered",
                        LocalDateTime.of(2026, 6, 12, 10, 30),
                        List.of(new AiAccessSessionService.AccessStepView(
                                "frontend-handler",
                                "注册页面动作 handler",
                                "PASS",
                                "handlers registered",
                                List.of("list.component.ts"),
                                Map.of(),
                                "cursor",
                                null,
                                null,
                                null)));
        when(scanProjectService.matchesAiCodingAccessKey(1L, "rac_valid")).thenReturn(true);
        when(accessSessionService.listPageAssistantSessions(1L, null)).thenReturn(List.of(summary));

        ResponseEntity<?> response = controller.listPageAssistantSessions(1L, null, "rac_valid");

        assertEquals(200, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        List<AiAccessSessionService.PageAssistantSessionSummaryView> body =
                (List<AiAccessSessionService.PageAssistantSessionSummaryView>) response.getBody();
        assertEquals("teamArchive.list", body.get(0).targetPageKey());
        assertEquals("IN_PROGRESS", body.get(0).completionState());
    }

    @Test
    void pageAssistantTargetBindAcceptsValidAiCodingAccessKey() {
        AiAccessSessionService.PageAssistantTargetRequest request =
                new AiAccessSessionService.PageAssistantTargetRequest(
                        "teamArchive.list",
                        "/team-build/depart-management",
                        List.of("getPageState", "search"));
        when(scanProjectService.matchesAiCodingAccessKey(1L, "rac_valid")).thenReturn(true);
        when(accessSessionService.bindPageAssistantTarget(1L, "rai_page", request)).thenReturn(pageAssistantSession());

        ResponseEntity<?> response = controller.bindPageAssistantSessionTarget(
                1L,
                "rai_page",
                "rac_valid",
                request);

        assertEquals(200, response.getStatusCode().value());
        Map<String, Object> body = objectMapper.convertValue(response.getBody(), new TypeReference<>() {
        });
        assertEquals("teamArchive.list", body.get("targetPageKey"));
    }

    @Test
    void pageAssistantCatalogSyncAcceptsValidAiCodingAccessKey() {
        RegistryCredentialEntity credential = new RegistryCredentialEntity();
        credential.setProjectCode("demo-service");
        credential.setAppKey("bzjs3");
        PageActionCatalogContracts.PageCatalogRegisterRequest request =
                new PageActionCatalogContracts.PageCatalogRegisterRequest(
                        "teamArchive.list",
                        "班组档案",
                        "/team-build/depart-management",
                        "ai-coding",
                        null,
                        true,
                        List.of(new PageActionCatalogContracts.PageActionDefinitionRequest(
                                "getPageState",
                                "读取页面状态",
                                "读取筛选、分页和表格",
                                false,
                                Map.of(),
                                Map.of(),
                                Map.of(),
                                List.of(),
                                Map.of("bridgeGlobal", "__REACHAI_PAGE_BRIDGE__"))),
                        Map.of("source", "Cursor"));
        when(scanProjectService.matchesAiCodingAccessKey(1L, "rac_valid")).thenReturn(true);
        when(scanProjectService.getById(1L)).thenReturn(project());
        when(registrySecurityService.findPrimaryActiveCredential("demo-service")).thenReturn(Optional.of(credential));
        when(pageActionCatalogService.registerFromProjectCredential(credential, request))
                .thenReturn(new PageCatalogRegisterResult("demo-service", "bzjs3", "teamArchive.list", 1));
        when(pageAssistantWorkflowBindingService.ensurePageWorkflowBinding(
                any(ScanProjectEntity.class),
                eq("teamArchive.list"),
                eq("/team-build/depart-management"),
                eq(List.of("getPageState"))))
                .thenReturn(new PageAssistantWorkflowBindingResult(
                        "agent-1",
                        "demo-service-page-copilot",
                        "workflow-1",
                        "demo-service-teamarchive_list-page-assistant",
                        42L));

        ResponseEntity<?> response = controller.syncPageAssistantCatalog(
                1L,
                "rai_page",
                "rac_valid",
                request);

        assertEquals(200, response.getStatusCode().value());
        Map<String, Object> body = objectMapper.convertValue(response.getBody(), new TypeReference<>() {
        });
        assertEquals("teamArchive.list", body.get("pageKey"));
        assertEquals(1, body.get("actionCount"));
        Map<String, Object> workflowBinding = objectMapper.convertValue(body.get("workflowBinding"), new TypeReference<>() {
        });
        assertEquals("agent-1", workflowBinding.get("agentId"));
        assertEquals("workflow-1", workflowBinding.get("workflowId"));
    }

    @Test
    void pageAssistantRegisterPageAcceptsValidAiCodingAccessKeyAndReturnsEvidence() {
        RegistryCredentialEntity credential = new RegistryCredentialEntity();
        credential.setProjectCode("demo-service");
        credential.setAppKey("bzjs3");
        AiAccessSessionService.PageAssistantPageRegisterRequest request =
                new AiAccessSessionService.PageAssistantPageRegisterRequest(
                        "rai_page",
                        "Cursor",
                        "teamArchive.list",
                        "班组档案",
                        "/team-build/depart-management",
                        "angular",
                        "12",
                        "__REACHAI_PAGE_BRIDGE__",
                        true,
                        List.of(new AiAccessSessionService.PageAssistantFileEvidence(
                                "src/app/list.component.ts",
                                "page-component",
                                true,
                                "abc")),
                        List.of(new PageActionCatalogContracts.PageActionDefinitionRequest(
                                "getPageState",
                                "读取页面状态",
                                "读取筛选、分页和表格",
                                false,
                                Map.of(),
                                Map.of(),
                                Map.of(),
                                List.of(),
                                Map.of("riskLevel", "LOW"))),
                        Map.of("build", Map.of("status", "PASS", "command", "npm run build")),
                        "done");
        AiAccessSessionService.PageAssistantCheckRunResponse checkRun =
                new AiAccessSessionService.PageAssistantCheckRunResponse(
                        new AiAccessSessionService.PageAssistantCheckResponse(
                                1L,
                                "demo-service",
                                "teamArchive.list",
                                "/team-build/depart-management",
                                "PASS",
                                List.of()),
                        pageAssistantSession());
        AiAccessSessionService.PageAssistantPageRegisterSessionResult sessionResult =
                new AiAccessSessionService.PageAssistantPageRegisterSessionResult(
                        pageAssistantSession(),
                        List.of(new AiAccessSessionService.PageAssistantFileEvidenceView(
                                "src/app/list.component.ts",
                                "page-component",
                                true,
                                "abc",
                                "VERIFIED",
                                "Local file hash captured.")));
        when(scanProjectService.matchesAiCodingAccessKey(1L, "rac_valid")).thenReturn(true);
        when(scanProjectService.getById(1L)).thenReturn(project());
        when(registrySecurityService.findPrimaryActiveCredential("demo-service")).thenReturn(Optional.of(credential));
        when(pageActionCatalogService.registerFromProjectCredential(
                eq(credential),
                any(PageActionCatalogContracts.PageCatalogRegisterRequest.class)))
                .thenReturn(new PageCatalogRegisterResult("demo-service", "bzjs3", "teamArchive.list", 1));
        when(accessSessionService.applyPageAssistantPageRegistration(
                1L,
                request,
                new PageCatalogRegisterResult("demo-service", "bzjs3", "teamArchive.list", 1)))
                .thenReturn(sessionResult);
        when(accessSessionService.runPageAssistantChecks(
                eq(1L),
                eq("rai_page"),
                any(AiAccessSessionService.PageAssistantCheckRequest.class)))
                .thenReturn(checkRun);
        when(pageAssistantWorkflowBindingService.ensurePageWorkflowBinding(
                any(ScanProjectEntity.class),
                eq("teamArchive.list"),
                eq("/team-build/depart-management"),
                eq(List.of("getPageState"))))
                .thenReturn(new PageAssistantWorkflowBindingResult(
                        "agent-1",
                        "demo-service-page-copilot",
                        "workflow-1",
                        "demo-service-teamarchive_list-page-assistant",
                        42L));

        ResponseEntity<?> response = controller.registerPageAssistantPage(1L, "rac_valid", request);

        assertEquals(200, response.getStatusCode().value());
        Map<String, Object> body = objectMapper.convertValue(response.getBody(), new TypeReference<>() {
        });
        assertTrue(body.containsKey("session"));
        assertTrue(body.containsKey("checkResult"));
        assertTrue(body.containsKey("registeredPage"));
        assertTrue(body.containsKey("registeredActions"));
        assertTrue(body.containsKey("fileEvidence"));
        Map<String, Object> workflowBinding = objectMapper.convertValue(body.get("workflowBinding"), new TypeReference<>() {
        });
        assertEquals("agent-1", workflowBinding.get("agentId"));
        assertEquals("workflow-1", workflowBinding.get("workflowId"));
    }

    @Test
    void pageAssistantRegisterPageRejectsInvalidAiCodingAccessKey() {
        when(scanProjectService.matchesAiCodingAccessKey(1L, "wrong-key")).thenReturn(false);

        ResponseEntity<?> response = controller.registerPageAssistantPage(
                1L,
                "wrong-key",
                new AiAccessSessionService.PageAssistantPageRegisterRequest(
                        null,
                        "Cursor",
                        "teamArchive.list",
                        "班组档案",
                        "/team-build/depart-management",
                        "angular",
                        "12",
                        "__REACHAI_PAGE_BRIDGE__",
                        true,
                        List.of(),
                        List.of(),
                        Map.of(),
                        "done"));

        assertEquals(403, response.getStatusCode().value());
    }

    @Test
    void latestSkillZipContainsSkillEntry() throws Exception {
        ResponseEntity<byte[]> response = controller.downloadLatestSkill();

        assertNotNull(response.getBody());
        assertTrue(response.getBody().length > 0);
        boolean hasSkill = false;
        boolean hasGatewayAndFrontendReference = false;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(response.getBody()))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if ("reachai-onboarding/SKILL.md".equals(entry.getName())) {
                    hasSkill = true;
                    String content = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                    assertTrue(content.contains("Gateway route and embed token broker summary"));
                    assertTrue(content.contains("/api/reachai/embed/**"));
                    assertTrue(content.contains("DedupeResponseHeader=Access-Control-Allow-Origin Access-Control-Allow-Credentials, RETAIN_FIRST"));
                    assertTrue(content.contains("Do not reuse the business login token"));
                    assertTrue(content.contains("access-sessions"));
                    assertTrue(content.contains("POST /api/embed/chat/sessions/{sessionId}/messages"));
                    assertTrue(content.contains("{ \"message\": \"...\" }"));
                    assertTrue(content.contains("Do not use the ReachAI platform base URL as a Maven repository"));
                    assertTrue(content.contains("/repository/**"));
                    assertTrue(content.contains("/npm/**"));
                }
                if ("reachai-onboarding/references/java-sdk-access.md".equals(entry.getName())) {
                    String content = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                    hasGatewayAndFrontendReference = content.contains("Gateway Route And Token Broker")
                            && content.contains("Front-End Embed Integration")
                            && content.contains("embed.defaultAgentKeySlug")
                            && content.contains("gateway authentication whitelist")
                            && content.contains("DedupeResponseHeader=Access-Control-Allow-Origin Access-Control-Allow-Credentials, RETAIN_FIRST")
                            && content.contains("Never use the business login token as the chat session token")
                            && content.contains("Do not send ReachAI chat requests as { \"content\": \"...\" }")
                            && content.contains("Maven Artifact Resolution")
                            && content.contains("Do not use the ReachAI platform base URL as a Maven repository")
                            && content.contains("/repository/**")
                            && content.contains("/npm/**")
                            && content.contains("access-sessions");
                }
            }
        }
        assertTrue(hasSkill);
        assertTrue(hasGatewayAndFrontendReference);
    }

    @Test
    void latestSkillMetadataPointsToDownloadUrl() {
        ResponseEntity<AiAssistController.SkillPackageResponse> response = controller.latestSkill(request());

        assertEquals("reachai-onboarding", response.getBody().name());
        assertTrue(response.getBody().downloadUrl().endsWith("/api/ai-assist/skills/reachai-onboarding/latest.zip"));
        assertTrue(response.getBody().files().stream().anyMatch(file -> "SKILL.md".equals(file.path())));
    }

    @Test
    void latestPageAssistantSkillMetadataPointsToDownloadUrl() {
        ResponseEntity<AiAssistController.SkillPackageResponse> response = controller.latestPageAssistantSkill(request());

        assertEquals("reachai-page-assistant-onboarding", response.getBody().name());
        assertTrue(response.getBody().downloadUrl().endsWith("/api/ai-assist/skills/reachai-page-assistant-onboarding/latest.zip"));
        assertTrue(response.getBody().files().stream().anyMatch(file -> "scripts/reachai-page-assistant.ps1".equals(file.path())));
    }

    @Test
    void latestWorkflowAiCodingSkillMetadataPointsToDownloadUrl() {
        ResponseEntity<AiAssistController.SkillPackageResponse> response = controller.latestWorkflowAiCodingSkill(request());

        assertEquals("workflow-ai-coding", response.getBody().name());
        assertTrue(response.getBody().downloadUrl().endsWith("/api/ai-assist/skills/workflow-ai-coding/latest.zip"));
        assertTrue(response.getBody().files().stream().anyMatch(file -> "references/workflow-apis.md".equals(file.path())));
    }

    @Test
    void latestWorkflowAiCodingSkillZipContainsSkillEntry() throws Exception {
        ResponseEntity<byte[]> response = controller.downloadLatestWorkflowAiCodingSkill();

        assertNotNull(response.getBody());
        assertTrue(response.getBody().length > 0);
        boolean hasSkill = false;
        boolean hasWorkflowApis = false;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(response.getBody()))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if ("workflow-ai-coding/SKILL.md".equals(entry.getName())) {
                    hasSkill = true;
                    String content = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                    assertTrue(content.contains("Workflow AI Coding"));
                    assertTrue(content.contains("manual publish"));
                }
                if ("workflow-ai-coding/references/workflow-apis.md".equals(entry.getName())) {
                    hasWorkflowApis = true;
                    String content = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                    assertTrue(content.contains("/api/workflows/ai-coding/workflows"));
                    assertTrue(content.contains("/ai-coding/versions"));
                }
            }
        }
        assertTrue(hasSkill);
        assertTrue(hasWorkflowApis);
    }

    @Test
    void downloadPageAssistantHelperScriptReturnsPs1Body() throws Exception {
        ResponseEntity<byte[]> response = controller.downloadPageAssistantHelperScript();

        assertNotNull(response.getBody());
        assertTrue(response.getBody().length > 0);
        String content = new String(response.getBody(), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(content.contains("function Invoke-Verify"));
        assertTrue(content.contains("browserRuntime"));
    }

    @Test
    void pageAssistantRegisterPageAcceptsStringFileShorthand() throws Exception {
        RegistryCredentialEntity credential = new RegistryCredentialEntity();
        credential.setProjectCode("demo-service");
        credential.setAppKey("bzjs3");
        String json = """
                {
                  "sessionId": "rai_page",
                  "toolName": "Cursor",
                  "pageKey": "teamArchive.list",
                  "pageName": "班组档案",
                  "routePattern": "/team-build/depart-management",
                  "framework": "angular",
                  "bridgeGlobal": "__REACHAI_PAGE_BRIDGE__",
                  "replaceActions": true,
                  "files": ["src/app/list.component.ts"],
                  "actions": [
                    {
                      "actionKey": "getPageState",
                      "title": "读取页面状态",
                      "description": "读取筛选、分页和表格",
                      "confirmRequired": false
                    }
                  ],
                  "verification": {
                    "browserStatic": { "status": "PASS", "message": "static only" },
                    "browserRuntime": { "status": "SKIPPED", "message": "no browser session" }
                  },
                  "handoffSummary": "done"
                }
                """;
        AiAccessSessionService.PageAssistantPageRegisterRequest request =
                objectMapper.readValue(json, AiAccessSessionService.PageAssistantPageRegisterRequest.class);
        AiAccessSessionService.PageAssistantCheckRunResponse checkRun =
                new AiAccessSessionService.PageAssistantCheckRunResponse(
                        new AiAccessSessionService.PageAssistantCheckResponse(
                                1L,
                                "demo-service",
                                "teamArchive.list",
                                "/team-build/depart-management",
                                "WARN",
                                List.of()),
                        pageAssistantSession());
        AiAccessSessionService.PageAssistantPageRegisterSessionResult sessionResult =
                new AiAccessSessionService.PageAssistantPageRegisterSessionResult(
                        pageAssistantSession(),
                        List.of(new AiAccessSessionService.PageAssistantFileEvidenceView(
                                "src/app/list.component.ts", "unknown", null, null,
                                "HASH_MISSING", "Path-only evidence without sha256; run helper verify for hash missing / local verify recommended.")));
        when(scanProjectService.matchesAiCodingAccessKey(1L, "rac_valid")).thenReturn(true);
        when(scanProjectService.getById(1L)).thenReturn(project());
        when(registrySecurityService.findPrimaryActiveCredential("demo-service")).thenReturn(Optional.of(credential));
        when(pageActionCatalogService.registerFromProjectCredential(
                eq(credential),
                any(PageActionCatalogContracts.PageCatalogRegisterRequest.class)))
                .thenReturn(new PageCatalogRegisterResult("demo-service", "bzjs3", "teamArchive.list", 1));
        when(accessSessionService.applyPageAssistantPageRegistration(
                eq(1L),
                any(AiAccessSessionService.PageAssistantPageRegisterRequest.class),
                any(PageCatalogRegisterResult.class)))
                .thenReturn(sessionResult);
        when(accessSessionService.runPageAssistantChecks(
                eq(1L),
                eq("rai_page"),
                any(AiAccessSessionService.PageAssistantCheckRequest.class)))
                .thenReturn(checkRun);
        when(pageAssistantWorkflowBindingService.ensurePageWorkflowBinding(
                any(ScanProjectEntity.class),
                eq("teamArchive.list"),
                eq("/team-build/depart-management"),
                eq(List.of("getPageState"))))
                .thenReturn(new PageAssistantWorkflowBindingResult(
                        "agent-1", "demo-service-page-copilot", "workflow-1",
                        "demo-service-teamarchive_list-page-assistant", 42L));

        assertEquals(1, request.files().size());
        assertEquals("src/app/list.component.ts", request.files().get(0).path());
        assertEquals("unknown", request.files().get(0).role());

        ResponseEntity<?> response = controller.registerPageAssistantPage(1L, "rac_valid", request);

        assertEquals(200, response.getStatusCode().value());
        verify(accessSessionService).applyPageAssistantPageRegistration(
                eq(1L),
                org.mockito.ArgumentMatchers.argThat(body ->
                        body.files() != null
                                && body.files().size() == 1
                                && "src/app/list.component.ts".equals(body.files().get(0).path())),
                any(PageCatalogRegisterResult.class));
    }

    private AgentEntryEntity embedEntry(String id, String keySlug, String name, String projectCode, boolean enabled) {
        AgentEntryEntity entry = new AgentEntryEntity();
        entry.setId(id);
        entry.setKeySlug(keySlug);
        entry.setName(name);
        entry.setProjectId(1L);
        entry.setProjectCode(projectCode);
        entry.setEnabled(enabled);
        return entry;
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setScheme("http");
        request.setServerName("localhost");
        request.setServerPort(18603);
        return request;
    }

    private ScanProjectEntity project() {
        ScanProjectEntity entity = new ScanProjectEntity();
        entity.setId(1L);
        entity.setName("Demo Service");
        entity.setProjectCode("demo-service");
        entity.setProjectKind("REGISTERED");
        entity.setEnvironment("dev");
        entity.setBaseUrl("http://localhost:8080");
        entity.setContextPath("");
        entity.setScanPath("");
        entity.setScanType("controller");
        entity.setToolCount(0);
        entity.setStatus("created");
        entity.setAiCodingAccessEnabled(true);
        entity.setAiCodingAccessKey("rac_demo");
        return entity;
    }

    private AiAccessSessionService.AccessSessionView accessSession() {
        return new AiAccessSessionService.AccessSessionView(
                "rai_demo",
                1L,
                "demo-service",
                "Cursor",
                "SDK_ACCESS",
                null,
                null,
                "RUNNING",
                10,
                3,
                0,
                "done",
                null,
                null,
                List.of(new AiAccessSessionService.AccessStepView(
                        "gateway-whitelist",
                        "配置 Embed 网关白名单",
                        "PASS",
                        "done",
                        List.of("gateway/SecurityConfiguration.java"),
                        Map.of("command", "mvn test"),
                        "cursor",
                        null,
                        null,
                        null)));
    }

    private AiAccessSessionService.AccessSessionView pageAssistantSession() {
        return new AiAccessSessionService.AccessSessionView(
                "rai_page",
                1L,
                "demo-service",
                "Cursor",
                "PAGE_ASSISTANT",
                "teamArchive.list",
                "/teams/archive",
                "RUNNING",
                8,
                2,
                0,
                "handlers registered",
                null,
                null,
                List.of(new AiAccessSessionService.AccessStepView(
                        "frontend-handler",
                        "注册页面动作 handler",
                        "PASS",
                        "handlers registered",
                        List.of("src/pages/team-archive/index.vue"),
                        Map.of("actions", List.of("getPageState", "search")),
                        "cursor",
                        null,
                        null,
                        null)));
    }
}
