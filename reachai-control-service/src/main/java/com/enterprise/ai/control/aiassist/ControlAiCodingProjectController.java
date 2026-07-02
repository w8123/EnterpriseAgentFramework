package com.enterprise.ai.control.aiassist;

import com.enterprise.ai.control.client.capability.CapabilityProjectOnboardingClient;
import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import feign.FeignException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@RestController
@RequestMapping("/api/ai-coding/projects/{projectId}")
@RequiredArgsConstructor
public class ControlAiCodingProjectController {

    private static final String AI_CODING_HEADER = "X-ReachAI-AiCoding-Key";
    private static final String PAGE_COPILOT_KIND = "PAGE_COPILOT";

    private final CapabilityProjectOnboardingClient capabilityClient;
    private final RuntimeProxyClient runtimeClient;

    @GetMapping("/manifest")
    public ResponseEntity<AiCodingGatewayManifest> manifest(@PathVariable Long projectId,
                                                            HttpServletRequest request) {
        try {
            Map<String, Object> project = capabilityClient.getOnboardingProjectById(projectId);
            String baseUrl = ControlAiAssistSkillController.requestBaseUrl(request);
            String root = baseUrl + "/api/ai-coding/projects/" + projectId;
            return ResponseEntity.ok(new AiCodingGatewayManifest(
                    "reachai.ai-coding.gateway.v1",
                    new AiCodingProject(
                            longValue(project.get("id")),
                            stringValue(project.get("projectCode")),
                            stringValue(project.get("name")),
                            stringValue(project.get("projectKind")),
                            stringValue(project.get("environment"))),
                    new AiCodingAuth(
                            AI_CODING_HEADER,
                            "ai-coding",
                            List.of(
                                    "Send the project AI Coding key in X-ReachAI-AiCoding-Key.",
                                    "Do not put aiCodingKey in query strings or generated browser runtime code.",
                                    "This manifest does not echo the raw project key.")),
                    gatewayEndpoints(baseUrl, root, projectId),
                    contextCandidateSubmission(root),
                    capabilities(root, baseUrl, projectId)));
        } catch (FeignException.NotFound ex) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/page-assistant/onboarding-manifest")
    public ResponseEntity<Map<String, Object>> pageAssistantManifest(@PathVariable Long projectId,
                                                                     @RequestParam(required = false) String pageKey,
                                                                     @RequestParam(required = false) String routePattern,
                                                                     @RequestParam(required = false) List<String> actionKeys,
                                                                     @RequestParam(required = false) String toolName,
                                                                     HttpServletRequest request) {
        try {
            Map<String, Object> project = capabilityClient.getOnboardingProjectById(projectId);
            String baseUrl = ControlAiAssistSkillController.requestBaseUrl(request);
            String root = baseUrl + "/api/ai-coding/projects/" + projectId + "/page-assistant";
            String sessionId = "page-assistant-" + projectId
                    + (StringUtils.hasText(pageKey) ? "-" + pageKey.trim() : "");
            return ResponseEntity.ok(pageAssistantManifestBody(project, root, sessionId, pageKey, routePattern));
        } catch (FeignException.NotFound ex) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/onboarding-manifest")
    public ResponseEntity<ControlAiAssistProjectController.OnboardingManifestResponse> onboardingManifest(
            @PathVariable Long projectId,
            HttpServletRequest request) {
        try {
            Map<String, Object> project = capabilityClient.getOnboardingProjectById(projectId);
            String baseUrl = ControlAiAssistSkillController.requestBaseUrl(request);
            String root = baseUrl + "/api/ai-coding/projects/" + projectId;
            ControlAiAssistProjectController.ProjectManifest projectManifest = projectManifest(project);
            ControlAiAssistProjectController.AgentProvisioningManifest provisioning =
                    agentProvisioningManifest(projectManifest, root);
            return ResponseEntity.ok(new ControlAiAssistProjectController.OnboardingManifestResponse(
                    "reachai.onboarding.v1",
                    projectManifest,
                    aiCodingAccess(project),
                    new ControlAiAssistProjectController.SdkManifest(
                            "1.0.0-SNAPSHOT",
                            List.of(
                                    new ControlAiAssistProjectController.MavenDependency(
                                            "com.enterprise.ai", "reachai-capability-sdk", "1.0.0-SNAPSHOT"),
                                    new ControlAiAssistProjectController.MavenDependency(
                                            "com.enterprise.ai", "reachai-spring-boot2-starter", "1.0.0-SNAPSHOT")),
                            new ControlAiAssistProjectController.ReachAiConfigManifest(
                                    baseUrl,
                                    projectManifest.registryAppKey(),
                                    "REACHAI_REGISTRY_APP_SECRET",
                                    projectManifest.projectCode(),
                                    projectManifest.name(),
                                    projectManifest.baseUrl(),
                                    projectManifest.contextPath(),
                                    projectManifest.environment())),
                    new ControlAiAssistProjectController.PlatformEndpoints(
                            baseUrl + "/api/ai-assist/skills/reachai-onboarding/latest.zip",
                            root + "/onboarding-manifest",
                            baseUrl + "/api/scan-projects/" + projectId + "/sdk-access-check",
                            baseUrl + "/api/scan-projects/" + projectId + "/tools/reconcile"),
                    new ControlAiAssistProjectController.EmbedManifest("/api/reachai/embed-token", null, null, List.of()),
                    provisioning,
                    agentWorkflowManifest(provisioning, baseUrl),
                    new ControlAiAssistProjectController.SecurityGuidance(
                            "REACHAI_REGISTRY_APP_SECRET",
                            "Do not paste or write the registry app secret into AI chat context.")));
        } catch (FeignException.NotFound ex) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/agents/provision")
    public ResponseEntity<Map<String, Object>> provisionProjectAgent(
            @PathVariable Long projectId,
            @RequestBody(required = false) Map<String, ?> request) {
        try {
            Map<String, Object> project = capabilityClient.getOnboardingProjectById(projectId);
            String projectCode = stringValue(project.get("projectCode"));
            String keySlug = pageCopilotKeySlug(projectCode, projectId);
            boolean ensureDefaultWorkflow = !Boolean.FALSE.equals(request == null ? null : request.get("ensureDefaultWorkflow"));

            RuntimeObject agent = findOrCreateAgent(projectId, projectCode, keySlug, stringValue(project.get("name")));
            RuntimeObject workflow = ensureDefaultWorkflow
                    ? findOrCreateDefaultWorkflow(projectId, projectCode, keySlug, stringValue(project.get("name")))
                    : RuntimeObject.empty(false, null);
            RuntimeObject binding = ensureDefaultWorkflow && workflow.body() != null
                    ? findOrCreateDefaultBinding(agent.id(), workflow.id(), projectCode)
                    : RuntimeObject.empty(false, null);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("schema", "agent-provisioning.v1");
            body.put("agent", agent.body());
            body.put("defaultWorkflow", workflow.body());
            body.put("defaultBinding", binding.body());
            body.put("createdAgent", agent.created());
            body.put("createdDefaultWorkflow", workflow.created());
            body.put("createdDefaultBinding", binding.created());
            return ResponseEntity.ok(body);
        } catch (FeignException.NotFound ex) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/access-sessions")
    public ResponseEntity<ControlAiAssistProjectController.AiAccessSessionView> startAccessSession(
            @PathVariable Long projectId,
            @RequestParam(required = false) String toolName) {
        try {
            Map<String, Object> project = capabilityClient.getOnboardingProjectById(projectId);
            return ResponseEntity.ok(sdkSession(project, toolName, "OPEN", null, null, null));
        } catch (FeignException.NotFound ex) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/access-sessions/latest")
    public ResponseEntity<ControlAiAssistProjectController.AiAccessSessionView> latestAccessSession(
            @PathVariable Long projectId) {
        try {
            Map<String, Object> project = capabilityClient.getOnboardingProjectById(projectId);
            return ResponseEntity.ok(sdkSession(project, null, "OPEN", "sdk-access-" + projectId, null, null));
        } catch (FeignException.NotFound ex) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/access-sessions/{sessionId}/steps/{stepKey}/report")
    public ResponseEntity<ControlAiAssistProjectController.AiAccessSessionView> reportAccessSessionStep(
            @PathVariable Long projectId,
            @PathVariable String sessionId,
            @PathVariable String stepKey,
            @RequestBody(required = false) Map<String, ?> request) {
        return accessSessionWithReportedStep(projectId, sessionId, stepKey, request, "SDK_ACCESS");
    }

    @PostMapping("/access-sessions/{sessionId}/checks/run")
    public ResponseEntity<ControlAiAssistProjectController.AiAccessCheckRunResponse> runAccessSessionChecks(
            @PathVariable Long projectId,
            @PathVariable String sessionId,
            @RequestBody(required = false) Map<String, ?> request) {
        try {
            Map<String, Object> project = capabilityClient.getOnboardingProjectById(projectId);
            var check = sdkAccessCheck(project);
            return ResponseEntity.ok(new ControlAiAssistProjectController.AiAccessCheckRunResponse(
                    check,
                    sdkSession(project, null, check.overallStatus(), sessionId, null, null)));
        } catch (FeignException.NotFound ex) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/page-assistant/sessions")
    public ResponseEntity<ControlAiAssistProjectController.AiAccessSessionView> startPageAssistantSession(
            @PathVariable Long projectId,
            @RequestBody(required = false) ControlAiAssistProjectController.PageAssistantSessionRequest request) {
        try {
            Map<String, Object> project = capabilityClient.getOnboardingProjectById(projectId);
            return ResponseEntity.ok(pageAssistantSessionView(
                    project,
                    request == null ? null : request.toolName(),
                    request == null ? null : request.pageKey(),
                    request == null ? null : request.routePattern(),
                    "OPEN",
                    null,
                    null,
                    null));
        } catch (FeignException.NotFound ex) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/page-assistant/sessions/latest")
    public ResponseEntity<ControlAiAssistProjectController.AiAccessSessionView> latestPageAssistantSession(
            @PathVariable Long projectId,
            @RequestParam(required = false) String pageKey) {
        try {
            Map<String, Object> project = capabilityClient.getOnboardingProjectById(projectId);
            return ResponseEntity.ok(pageAssistantSessionView(project, null, pageKey, null, "OPEN", null, null, null));
        } catch (FeignException.NotFound ex) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/page-assistant/sessions")
    public ResponseEntity<List<ControlAiAssistProjectController.PageAssistantSessionSummary>> pageAssistantSessions(
            @PathVariable Long projectId,
            @RequestParam(required = false) String pageKey) {
        try {
            Map<String, Object> project = capabilityClient.getOnboardingProjectById(projectId);
            var session = pageAssistantSessionView(project, null, pageKey, null, "OPEN", null, null, null);
            return ResponseEntity.ok(List.of(toPageAssistantSessionSummary(session, 0)));
        } catch (FeignException.NotFound ex) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/page-assistant/sessions/{sessionId}/steps/{stepKey}/report")
    public ResponseEntity<ControlAiAssistProjectController.AiAccessSessionView> reportPageAssistantSessionStep(
            @PathVariable Long projectId,
            @PathVariable String sessionId,
            @PathVariable String stepKey,
            @RequestBody(required = false) Map<String, ?> request) {
        return accessSessionWithReportedStep(projectId, sessionId, stepKey, request, "PAGE_ASSISTANT");
    }

    @PutMapping("/page-assistant/sessions/{sessionId}/target")
    public ResponseEntity<ControlAiAssistProjectController.AiAccessSessionView> bindPageAssistantTarget(
            @PathVariable Long projectId,
            @PathVariable String sessionId,
            @RequestBody(required = false) ControlAiAssistProjectController.PageAssistantTargetRequest request) {
        try {
            Map<String, Object> project = capabilityClient.getOnboardingProjectById(projectId);
            return ResponseEntity.ok(pageAssistantSessionView(
                    project,
                    null,
                    request == null ? null : request.pageKey(),
                    request == null ? null : request.routePattern(),
                    "OPEN",
                    sessionId,
                    null,
                    null));
        } catch (FeignException.NotFound ex) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/page-assistant/sessions/{sessionId}/catalog/sync")
    public ResponseEntity<ControlAiAssistProjectController.PageAssistantCatalogSyncResponse> syncPageAssistantCatalog(
            @PathVariable Long projectId,
            @PathVariable String sessionId,
            @RequestBody(required = false) ControlAiAssistProjectController.PageAssistantCatalogSyncRequest request) {
        try {
            Map<String, Object> project = capabilityClient.getOnboardingProjectById(projectId);
            int actionCount = request == null || request.actions() == null ? 0 : request.actions().size();
            var session = pageAssistantSessionView(project, null,
                    request == null ? null : request.pageKey(),
                    request == null ? null : request.routePattern(),
                    "PASS",
                    sessionId,
                    null,
                    null);
            return ResponseEntity.ok(new ControlAiAssistProjectController.PageAssistantCatalogSyncResponse(
                    stringValue(project.get("projectCode")),
                    stringValue(project.get("projectCode")),
                    request == null ? null : request.pageKey(),
                    actionCount,
                    session,
                    null));
        } catch (FeignException.NotFound ex) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/page-assistant/sessions/{sessionId}/checks/run")
    public ResponseEntity<ControlAiAssistProjectController.PageAssistantCheckRunResponse> runPageAssistantChecks(
            @PathVariable Long projectId,
            @PathVariable String sessionId,
            @RequestBody(required = false) Map<String, ?> request) {
        try {
            Map<String, Object> project = capabilityClient.getOnboardingProjectById(projectId);
            String pageKey = stringValue(request == null ? null : request.get("pageKey"));
            String routePattern = stringValue(request == null ? null : request.get("routePattern"));
            var check = pageAssistantCheck(project, pageKey, routePattern);
            return ResponseEntity.ok(new ControlAiAssistProjectController.PageAssistantCheckRunResponse(
                    check,
                    pageAssistantSessionView(project, null, pageKey, routePattern, check.overallStatus(), sessionId, null, null)));
        } catch (FeignException.NotFound ex) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/page-assistant/pages/register")
    public ResponseEntity<ControlAiAssistProjectController.PageAssistantPageRegisterResponse> registerPageAssistantPage(
            @PathVariable Long projectId,
            @RequestBody(required = false) ControlAiAssistProjectController.PageAssistantPageRegisterRequest request) {
        try {
            Map<String, Object> project = capabilityClient.getOnboardingProjectById(projectId);
            var session = pageAssistantSessionView(project,
                    request == null ? null : request.toolName(),
                    request == null ? null : request.pageKey(),
                    request == null ? null : request.routePattern(),
                    "PASS",
                    request == null ? null : request.sessionId(),
                    null,
                    null);
            List<String> registeredActions = request == null || request.actions() == null
                    ? List.of()
                    : request.actions().stream().map(ControlAiAssistProjectController.PageAssistantCatalogActionRequest::actionKey).toList();
            return ResponseEntity.ok(new ControlAiAssistProjectController.PageAssistantPageRegisterResponse(
                    session,
                    pageAssistantCheck(project, request == null ? null : request.pageKey(), request == null ? null : request.routePattern()),
                    new ControlAiAssistProjectController.RegisteredPage(
                            stringValue(project.get("projectCode")),
                            stringValue(project.get("projectCode")),
                            request == null ? null : request.pageKey(),
                            request == null ? null : request.pageName(),
                            request == null ? null : request.routePattern(),
                            request == null ? null : request.framework(),
                            request == null ? null : request.bridgeGlobal()),
                    registeredActions,
                    request == null || request.files() == null ? List.of() : request.files(),
                    null));
        } catch (FeignException.NotFound ex) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/page-assistant/sessions/{sessionId}/workflow-ai-coding-result")
    public ResponseEntity<ControlAiAssistProjectController.AiAccessSessionView> reportPageAssistantWorkflowAiCodingResult(
            @PathVariable Long projectId,
            @PathVariable String sessionId,
            @RequestBody(required = false) Map<String, ?> request) {
        return pageAssistantWorkflowAiCodingSession(projectId, sessionId, request, "PASS");
    }

    @DeleteMapping("/page-assistant/sessions/{sessionId}/workflow-ai-coding-result")
    public ResponseEntity<ControlAiAssistProjectController.AiAccessSessionView> resetPageAssistantWorkflowAiCodingResult(
            @PathVariable Long projectId,
            @PathVariable String sessionId) {
        return pageAssistantWorkflowAiCodingSession(projectId, sessionId, Map.of(), "OPEN");
    }

    @PostMapping("/context-candidates")
    public ResponseEntity<Map<String, Object>> createContextCandidate(
            @PathVariable Long projectId,
            @RequestBody(required = false) Map<String, ?> request) {
        try {
            Map<String, Object> project = capabilityClient.getOnboardingProjectById(projectId);
            return ResponseEntity.ok(contextCandidate(project, request));
        } catch (FeignException.NotFound ex) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/context-candidates/batch")
    public ResponseEntity<List<Map<String, Object>>> createContextCandidateBatch(
            @PathVariable Long projectId,
            @RequestBody(required = false) List<Map<String, ?>> requests) {
        try {
            Map<String, Object> project = capabilityClient.getOnboardingProjectById(projectId);
            List<Map<String, Object>> result = new ArrayList<>();
            for (Map<String, ?> item : requests == null ? List.<Map<String, ?>>of() : requests) {
                result.add(contextCandidate(project, item));
            }
            return ResponseEntity.ok(result);
        } catch (FeignException.NotFound ex) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/context-candidates")
    public ResponseEntity<List<Map<String, Object>>> listContextCandidates(
            @PathVariable Long projectId,
            @RequestParam(required = false) String traceId,
            @RequestParam(required = false) String status) {
        try {
            capabilityClient.getOnboardingProjectById(projectId);
            return ResponseEntity.ok(List.of());
        } catch (FeignException.NotFound ex) {
            return ResponseEntity.notFound().build();
        }
    }

    ResponseEntity<Map<String, Object>> pageAssistantManifest(Long projectId,
                                                              String pageKey,
                                                              String routePattern,
                                                              HttpServletRequest request) {
        return pageAssistantManifest(projectId, pageKey, routePattern, null, null, request);
    }

    private ControlAiAssistProjectController.ProjectManifest projectManifest(Map<String, Object> project) {
        return new ControlAiAssistProjectController.ProjectManifest(
                longValue(project.get("id")),
                stringValue(project.get("name")),
                stringValue(project.get("projectCode")),
                stringValue(project.get("projectKind")),
                stringValue(project.get("environment")),
                stringValue(project.get("baseUrl")),
                emptyToNull(stringValue(project.get("contextPath"))),
                stringValue(project.get("registryAppKey")),
                booleanValue(project.get("registryCredentialConfigured")));
    }

    private ControlAiAssistProjectController.AiCodingAccessManifest aiCodingAccess(Map<String, Object> project) {
        Map<String, Object> value = mapValue(project.get("aiCodingAccess"));
        return new ControlAiAssistProjectController.AiCodingAccessManifest(
                booleanValue(value.get("enabled")),
                stringValue(value.get("accessKey")));
    }

    private ControlAiAssistProjectController.AgentProvisioningManifest agentProvisioningManifest(
            ControlAiAssistProjectController.ProjectManifest project,
            String root) {
        return new ControlAiAssistProjectController.AgentProvisioningManifest(
                "agent-provisioning.v1",
                PAGE_COPILOT_KIND,
                pageCopilotKeySlug(project.projectCode(), project.id()),
                root + "/agents/provision",
                true,
                true,
                true,
                List.of(
                        "Call provisionAgentUrl before wiring embedded chat.",
                        "Use response.agent.keySlug as the business frontend agentId.",
                        "Do not ask the user to manually create or choose a project Agent during SDK onboarding."));
    }

    private ControlAiAssistProjectController.AgentWorkflowManifest agentWorkflowManifest(
            ControlAiAssistProjectController.AgentProvisioningManifest provisioning,
            String baseUrl) {
        String globalAgentKeySlug = provisioning.defaultKeySlug();
        return new ControlAiAssistProjectController.AgentWorkflowManifest(
                "agent-workflow.decoupled.v1",
                globalAgentKeySlug,
                PAGE_COPILOT_KIND,
                "runtime_workflow",
                "SDK_GRAPH",
                "Bind page/action/intent workflows to the project page copilot Agent instead of creating one agent per workflow.",
                new ControlAiAssistProjectController.AgentWorkflowEndpoints(
                        baseUrl + "/api/agents",
                        baseUrl + "/api/workflows",
                        baseUrl + "/api/agents/" + globalAgentKeySlug + "/workflow-bindings",
                        baseUrl + "/api/agents/" + globalAgentKeySlug + "/workflow-bindings/resolve-preview"),
                new ControlAiAssistProjectController.WorkflowAiCodingManifest(
                        baseUrl + "/api/ai-assist/skills/workflow-ai-coding/latest.zip",
                        baseUrl + "/api/workflows/ai-coding/workflows",
                        baseUrl + "/api/workflows/{workflowId}/ai-coding/context",
                        baseUrl + "/api/workflows/{workflowId}/ai-coding/patch",
                        baseUrl + "/api/workflows/{workflowId}/ai-coding/validate",
                        baseUrl + "/api/workflows/{workflowId}/ai-coding/run",
                        baseUrl + "/api/workflows/{workflowId}/ai-coding/versions",
                        baseUrl + "/api/workflows/{workflowId}/ai-coding/publish",
                        baseUrl + "/api/workflows/{workflowId}/ai-coding/runs",
                        List.of(
                                "Download and install the workflow-ai-coding skill before editing graphs from AI tools.",
                                "Read /context before patch; use workflow.updatedAt as baseRevision when saving.")),
                List.of(
                        "Provision or reuse one project-level PAGE_COPILOT Agent entry.",
                        "Store every executable graph as an runtime_workflow draft or version."));
    }

    private RuntimeObject findOrCreateAgent(Long projectId, String projectCode, String keySlug, String projectName) {
        List<Map<String, Object>> agents = responseList(runtimeClient.listAgents(projectId, projectCode, PAGE_COPILOT_KIND));
        Map<String, Object> existing = agents.stream()
                .filter(item -> Objects.equals(keySlug, stringValue(item.get("keySlug"))))
                .findFirst()
                .orElse(null);
        if (existing != null) {
            return new RuntimeObject(false, existing);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("projectId", projectId);
        body.put("projectCode", projectCode);
        body.put("keySlug", keySlug);
        body.put("name", firstText(projectName, projectCode, "Project") + " Page Copilot");
        body.put("description", "Project page copilot Agent for embedded chat and Workflow routing.");
        body.put("agentKind", PAGE_COPILOT_KIND);
        body.put("visibility", "PROJECT");
        body.put("systemPrompt", "You are the project's page copilot. Route executable work to bound Workflows.");
        body.put("entryConfigJson", "{\"source\":\"ai-coding-gateway\",\"purpose\":\"page-copilot\"}");
        body.put("enabled", true);
        return new RuntimeObject(true, responseMap(runtimeClient.createAgent(body)));
    }

    private RuntimeObject findOrCreateDefaultWorkflow(Long projectId, String projectCode, String agentKeySlug, String projectName) {
        String workflowKeySlug = agentKeySlug + "-default";
        List<Map<String, Object>> workflows = responseList(
                runtimeClient.listWorkflows(projectId, projectCode, "PAGE_COPILOT_DEFAULT", null));
        Map<String, Object> existing = workflows.stream()
                .filter(item -> Objects.equals(workflowKeySlug, stringValue(item.get("keySlug"))))
                .findFirst()
                .orElse(null);
        if (existing != null) {
            return new RuntimeObject(false, existing);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("projectId", projectId);
        body.put("projectCode", projectCode);
        body.put("keySlug", workflowKeySlug);
        body.put("name", firstText(projectName, projectCode, "Project") + " Page Copilot Default Workflow");
        body.put("description", "Default placeholder workflow for project page copilot routing.");
        body.put("workflowType", "PAGE_COPILOT_DEFAULT");
        body.put("runtimeType", "LANGGRAPH4J");
        body.put("status", "DRAFT");
        body.put("managedBy", "SDK_ONBOARDING");
        body.put("graphSpecJson", "{\"version\":\"1.0\",\"nodes\":[],\"edges\":[]}");
        body.put("canvasJson", "{\"nodes\":[],\"edges\":[]}");
        return new RuntimeObject(true, responseMap(runtimeClient.createWorkflow(body)));
    }

    private RuntimeObject findOrCreateDefaultBinding(String agentId, String workflowId, String projectCode) {
        if (!StringUtils.hasText(agentId) || !StringUtils.hasText(workflowId)) {
            return RuntimeObject.empty(false, null);
        }
        List<Map<String, Object>> bindings = responseList(runtimeClient.listAgentWorkflowBindings(agentId));
        Map<String, Object> existing = bindings.stream()
                .filter(item -> Objects.equals(workflowId, stringValue(item.get("workflowId"))))
                .filter(item -> "DEFAULT".equalsIgnoreCase(stringValue(item.get("bindingType"))))
                .findFirst()
                .orElse(null);
        if (existing != null) {
            return new RuntimeObject(false, existing);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("agentId", agentId);
        body.put("workflowId", workflowId);
        body.put("projectCode", projectCode);
        body.put("bindingType", "DEFAULT");
        body.put("priority", 0);
        body.put("enabled", true);
        body.put("metadataJson", "{\"source\":\"ai-coding-gateway\"}");
        return new RuntimeObject(true, responseMap(runtimeClient.createAgentWorkflowBinding(agentId, body)));
    }

    private ResponseEntity<ControlAiAssistProjectController.AiAccessSessionView> accessSessionWithReportedStep(
            Long projectId,
            String sessionId,
            String stepKey,
            Map<String, ?> request,
            String scenario) {
        try {
            Map<String, Object> project = capabilityClient.getOnboardingProjectById(projectId);
            String status = firstText(stringValue(request == null ? null : request.get("status")), "PASS");
            String message = stringValue(request == null ? null : request.get("message"));
            return ResponseEntity.ok("PAGE_ASSISTANT".equals(scenario)
                    ? pageAssistantSessionView(project, null, null, null, status, sessionId, stepKey, message)
                    : sdkSession(project, null, status, sessionId, stepKey, message));
        } catch (FeignException.NotFound ex) {
            return ResponseEntity.notFound().build();
        }
    }

    private ControlAiAssistProjectController.AiAccessSessionView sdkSession(Map<String, Object> project,
                                                                            String toolName,
                                                                            String status,
                                                                            String requestedSessionId,
                                                                            String reportedStepKey,
                                                                            String reportedMessage) {
        Long projectId = longValue(project.get("id"));
        String now = Instant.now().toString();
        String sessionId = StringUtils.hasText(requestedSessionId) ? requestedSessionId : "sdk-access-" + projectId;
        List<ControlAiAssistProjectController.AiAccessStepView> steps = reportedStep(reportedStepKey, status, reportedMessage);
        return new ControlAiAssistProjectController.AiAccessSessionView(
                sessionId,
                projectId,
                stringValue(project.get("projectCode")),
                StringUtils.hasText(toolName) ? toolName : null,
                "SDK_ACCESS",
                null,
                null,
                status,
                reportedStepKey == null ? 6 : steps.size(),
                completedSteps(steps),
                failedSteps(steps),
                "PASS".equals(status) ? "SDK access check passed" : "SDK access session is ready",
                now,
                now,
                steps);
    }

    private ControlAiAssistProjectController.AiAccessSessionView pageAssistantSessionView(Map<String, Object> project,
                                                                                          String toolName,
                                                                                          String pageKey,
                                                                                          String routePattern,
                                                                                          String status,
                                                                                          String requestedSessionId,
                                                                                          String reportedStepKey,
                                                                                          String reportedMessage) {
        Long projectId = longValue(project.get("id"));
        String normalizedPageKey = StringUtils.hasText(pageKey) ? pageKey.trim() : null;
        String sessionId = StringUtils.hasText(requestedSessionId)
                ? requestedSessionId
                : "page-assistant-" + projectId + (normalizedPageKey == null ? "" : "-" + normalizedPageKey);
        String now = Instant.now().toString();
        List<ControlAiAssistProjectController.AiAccessStepView> steps = reportedStep(reportedStepKey, status, reportedMessage);
        return new ControlAiAssistProjectController.AiAccessSessionView(
                sessionId,
                projectId,
                stringValue(project.get("projectCode")),
                StringUtils.hasText(toolName) ? toolName : null,
                "PAGE_ASSISTANT",
                normalizedPageKey,
                StringUtils.hasText(routePattern) ? routePattern.trim() : null,
                status,
                reportedStepKey == null ? 5 : steps.size(),
                completedSteps(steps),
                failedSteps(steps),
                "PASS".equals(status) ? "Page Assistant checks passed" : "Page Assistant session is ready",
                now,
                now,
                steps);
    }

    private List<ControlAiAssistProjectController.AiAccessStepView> reportedStep(String stepKey,
                                                                                  String status,
                                                                                  String message) {
        if (!StringUtils.hasText(stepKey)) {
            return List.of();
        }
        return List.of(new ControlAiAssistProjectController.AiAccessStepView(
                stepKey.trim(),
                stepKey.trim(),
                StringUtils.hasText(status) ? status.trim() : "PASS",
                message,
                List.of(),
                Map.of(),
                "ai-coding",
                null,
                Instant.now().toString(),
                Instant.now().toString()));
    }

    private int completedSteps(List<ControlAiAssistProjectController.AiAccessStepView> steps) {
        return (int) steps.stream().filter(step -> "PASS".equals(step.status())).count();
    }

    private int failedSteps(List<ControlAiAssistProjectController.AiAccessStepView> steps) {
        return (int) steps.stream().filter(step -> "FAIL".equals(step.status())).count();
    }

    private ControlAiAssistProjectController.SdkAccessCheckResponse sdkAccessCheck(Map<String, Object> project) {
        boolean credentialConfigured = booleanValue(project.get("registryCredentialConfigured"));
        boolean aiCodingEnabled = aiCodingAccess(project).enabled();
        String overall = credentialConfigured && aiCodingEnabled ? "PASS" : "WARN";
        return new ControlAiAssistProjectController.SdkAccessCheckResponse(
                longValue(project.get("id")),
                stringValue(project.get("projectCode")),
                overall,
                List.of(
                        new ControlAiAssistProjectController.SdkAccessReadiness("CODE_READY", "Code", overall, "SDK onboarding route is available"),
                        new ControlAiAssistProjectController.SdkAccessReadiness("RUNTIME_READY", "Runtime", overall, "Runtime readiness requires business service heartbeat"),
                        new ControlAiAssistProjectController.SdkAccessReadiness("E2E_READY", "E2E", overall, "Run a business API call to complete final verification")),
                List.of(
                        new ControlAiAssistProjectController.SdkAccessCheckItem("PROJECT", "Project", "PASS", "Project loaded", null),
                        new ControlAiAssistProjectController.SdkAccessCheckItem("REGISTRY_CREDENTIAL", "Registry credential",
                                credentialConfigured ? "PASS" : "WARN",
                                credentialConfigured ? "Active registry credential configured" : "Active registry credential is not configured",
                                null),
                        new ControlAiAssistProjectController.SdkAccessCheckItem("AI_CODING_ACCESS", "AI Coding access",
                                aiCodingEnabled ? "PASS" : "WARN",
                                aiCodingEnabled ? "AI Coding access enabled" : "AI Coding access is disabled",
                                null)));
    }

    private ControlAiAssistProjectController.PageAssistantCheckResponse pageAssistantCheck(Map<String, Object> project,
                                                                                           String pageKey,
                                                                                           String routePattern) {
        boolean targetReady = StringUtils.hasText(pageKey) || StringUtils.hasText(routePattern);
        boolean aiCodingEnabled = aiCodingAccess(project).enabled();
        String overall = targetReady && aiCodingEnabled ? "PASS" : "WARN";
        return new ControlAiAssistProjectController.PageAssistantCheckResponse(
                longValue(project.get("id")),
                stringValue(project.get("projectCode")),
                StringUtils.hasText(pageKey) ? pageKey.trim() : null,
                StringUtils.hasText(routePattern) ? routePattern.trim() : null,
                overall,
                List.of(
                        new ControlAiAssistProjectController.PageAssistantCheckItem("PROJECT", "Project", "PASS", "Project loaded", null),
                        new ControlAiAssistProjectController.PageAssistantCheckItem("TARGET_PAGE", "Target page",
                                targetReady ? "PASS" : "WARN",
                                targetReady ? "Target page configured" : "pageKey or routePattern is missing",
                                null),
                        new ControlAiAssistProjectController.PageAssistantCheckItem("AI_CODING_ACCESS", "AI Coding access",
                                aiCodingEnabled ? "PASS" : "WARN",
                                aiCodingEnabled ? "AI Coding access enabled" : "AI Coding access is disabled",
                                null)));
    }

    private ControlAiAssistProjectController.PageAssistantSessionSummary toPageAssistantSessionSummary(
            ControlAiAssistProjectController.AiAccessSessionView session,
            int actionCount) {
        return new ControlAiAssistProjectController.PageAssistantSessionSummary(
                session.sessionId(),
                session.projectId(),
                session.projectCode(),
                session.toolName(),
                session.targetPageKey(),
                session.targetRoute(),
                session.status(),
                "PASS".equals(session.status()) ? "COMPLETED" : "WAITING_TARGET",
                session.totalSteps(),
                session.completedSteps(),
                session.failedSteps(),
                actionCount,
                session.lastMessage(),
                session.updatedAt(),
                session.steps());
    }

    private ResponseEntity<ControlAiAssistProjectController.AiAccessSessionView> pageAssistantWorkflowAiCodingSession(
            Long projectId,
            String sessionId,
            Map<String, ?> request,
            String status) {
        try {
            Map<String, Object> project = capabilityClient.getOnboardingProjectById(projectId);
            return ResponseEntity.ok(pageAssistantSessionView(
                    project,
                    null,
                    stringValue(request == null ? null : request.get("pageKey")),
                    stringValue(request == null ? null : request.get("routePattern")),
                    status,
                    sessionId,
                    null,
                    null));
        } catch (FeignException.NotFound ex) {
            return ResponseEntity.notFound().build();
        }
    }

    private Map<String, Object> contextCandidate(Map<String, Object> project, Map<String, ?> request) {
        String submissionId = "ai-coding-submission-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", submissionId);
        body.put("submissionId", submissionId);
        body.put("tenantId", "default");
        body.put("memoryLane", "PROJECT_DEV");
        body.put("projectId", longValue(project.get("id")));
        body.put("projectCode", stringValue(project.get("projectCode")));
        body.put("content", stringValue(request == null ? null : request.get("content")));
        body.put("candidateType", firstText(stringValue(request == null ? null : request.get("candidateType")), "NOTE"));
        body.put("sourceType", firstText(stringValue(request == null ? null : request.get("sourceType")), "CODE"));
        body.put("status", "PENDING");
        body.put("traceId", submissionId);
        body.put("origin", "ai-coding");
        body.put("createdAt", Instant.now().toString());
        body.put("updatedAt", Instant.now().toString());
        return body;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> responseList(ResponseEntity<Object> response) {
        Object body = response == null ? null : response.getBody();
        if (body instanceof Collection<?> collection) {
            return collection.stream()
                    .filter(Map.class::isInstance)
                    .map(item -> (Map<String, Object>) item)
                    .toList();
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> responseMap(ResponseEntity<Object> response) {
        Object body = response == null ? null : response.getBody();
        return body instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private String pageCopilotKeySlug(String projectCode, Long projectId) {
        String source = StringUtils.hasText(projectCode) ? projectCode.trim() : "project-" + projectId;
        return source.toLowerCase()
                .replaceAll("[^a-z0-9_-]+", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "") + "-page-copilot";
    }

    private String firstText(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private String emptyToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private AiCodingGatewayEndpoints gatewayEndpoints(String baseUrl, String root, Long projectId) {
        String contextCandidates = root + "/context-candidates";
        return new AiCodingGatewayEndpoints(
                root + "/manifest",
                contextCandidates,
                contextCandidates + "/batch",
                contextCandidates + "?traceId={submissionId}&status=PENDING",
                root + "/onboarding-manifest",
                root + "/access-sessions",
                root + "/access-sessions/latest",
                root + "/page-assistant/onboarding-manifest",
                root + "/page-assistant/sessions",
                baseUrl + "/api/workflows/ai-coding/workflows",
                baseUrl + "/api/workflows/{workflowId}/ai-coding/context",
                baseUrl + "/api/workflows/{workflowId}/ai-coding/patch",
                baseUrl + "/api/workflows/{workflowId}/ai-coding/validate",
                baseUrl + "/api/workflows/{workflowId}/ai-coding/run",
                baseUrl + "/api/workflows/{workflowId}/ai-coding/versions",
                baseUrl + "/api/workflows/{workflowId}/ai-coding/publish",
                baseUrl + "/api/workflows/{workflowId}/ai-coding/runs",
                baseUrl + "/context-governance?projectId=" + projectId + "&tab=candidates",
                baseUrl + "/context-governance?projectId=" + projectId + "&tab=candidates&traceId={submissionId}");
    }

    private ContextCandidateSubmission contextCandidateSubmission(String root) {
        return new ContextCandidateSubmission(
                "reachai.context-candidate-submission.v1",
                root + "/context-candidates",
                root + "/context-candidates/batch",
                "PENDING_HUMAN_REVIEW",
                "PROJECT_DEV",
                "default",
                "CODE",
                "NOTE",
                List.of("content"),
                List.of("NOTE", "API", "RULE", "DECISION"),
                List.of("CODE", "DOC", "CHAT", "AI_CODING"),
                new TraceMetadata(
                        "traceMetadata",
                        "ai-coding-submission-",
                        "ai-coding",
                        "Server may generate traceId when absent.",
                        "Client may provide sessionId for correlation."),
                List.of(
                        "tenantId",
                        "memoryLane",
                        "projectId",
                        "projectCode",
                        "proposedBy",
                        "traceId",
                        "sessionId",
                        "origin",
                        "visibility",
                        "confidence",
                        "trustLevel",
                        "expiresAt",
                        "userId",
                        "globalUserId",
                        "externalUserId"),
                List.of(
                        "Submit project development context as candidates; human review is still required.",
                        "Use status URL with the returned submissionId to check pending candidates.",
                        "Do not submit secrets or raw customer data."));
    }

    private List<AiCodingCapability> capabilities(String root, String baseUrl, Long projectId) {
        return List.of(
                new AiCodingCapability(
                        "SDK_ACCESS",
                        "SDK 快速接入",
                        "PROJECT",
                        root + "/onboarding-manifest",
                        List.of("Use for backend starter, registry credential, and SDK graph onboarding.")),
                new AiCodingCapability(
                        "PAGE_ASSISTANT",
                        "页面助手接入",
                        "PAGE",
                        root + "/page-assistant/onboarding-manifest",
                        List.of("Use for page bridge, page action catalog, and page assistant verification.")),
                new AiCodingCapability(
                        "WORKFLOW_AI_CODING",
                        "Workflow AI Coding",
                        "WORKFLOW",
                        baseUrl + "/api/workflows/ai-coding/workflows",
                        List.of("Use workflow scoped endpoints for GraphSpec draft edits and validation.")),
                new AiCodingCapability(
                        "CONTEXT_CANDIDATES",
                        "上下文候选提交",
                        "PROJECT",
                        root + "/context-candidates",
                        List.of("Submit PROJECT_DEV candidates for review under project " + projectId + ".")));
    }

    private Map<String, Object> pageAssistantManifestBody(Map<String, Object> project,
                                                          String root,
                                                          String sessionId,
                                                          String pageKey,
                                                          String routePattern) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schema", "reachai.page-assistant.onboarding.v1");
        body.put("project", Map.of(
                "id", longValue(project.get("id")),
                "name", nullToEmpty(project.get("name")),
                "projectCode", nullToEmpty(project.get("projectCode")),
                "projectKind", nullToEmpty(project.get("projectKind")),
                "environment", nullToEmpty(project.get("environment")),
                "registryCredentialConfigured", booleanValue(project.get("registryCredentialConfigured"))));
        body.put("aiCodingAccess", Map.of(
                "enabled", aiCodingAccessEnabled(project),
                "accessKey", ""));
        body.put("auth", Map.of(
                "mode", "ai-coding-key",
                "headerName", AI_CODING_HEADER,
                "keyEnv", "REACHAI_AI_CODING_KEY",
                "externalToolPath", root + "/**",
                "guidance", List.of("Send the key by header. Do not put it in URLs.")));
        body.put("target", Map.of(
                "pageKey", nullToEmpty(pageKey),
                "routePattern", nullToEmpty(routePattern),
                "actionKeys", List.of()));
        body.put("session", pageAssistantSession(project, sessionId, pageKey, routePattern, "OPEN"));
        body.put("endpoints", Map.of(
                "manifestUrl", root + "/onboarding-manifest",
                "latestSessionUrl", root + "/sessions/latest",
                "stepReportUrl", root + "/sessions/" + sessionId + "/steps/{stepKey}/report",
                "targetBindUrl", root + "/sessions/" + sessionId + "/target",
                "catalogSyncUrl", root + "/sessions/" + sessionId + "/catalog/sync",
                "checksRunUrl", root + "/sessions/" + sessionId + "/checks/run",
                "registerPageUrl", root + "/pages/register"));
        body.put("security", Map.of(
                "appSecretEnv", "REACHAI_REGISTRY_APP_SECRET",
                "message", "Do not store aiCodingKey or registry secrets in browser runtime code."));
        return body;
    }

    private Map<String, Object> pageAssistantSession(Map<String, Object> project,
                                                     String sessionId,
                                                     String pageKey,
                                                     String routePattern,
                                                     String status) {
        String now = Instant.now().toString();
        Map<String, Object> session = new LinkedHashMap<>();
        session.put("sessionId", sessionId);
        session.put("projectId", longValue(project.get("id")));
        session.put("projectCode", nullToEmpty(project.get("projectCode")));
        session.put("scenario", "PAGE_ASSISTANT");
        session.put("targetPageKey", nullToEmpty(pageKey));
        session.put("targetRoute", nullToEmpty(routePattern));
        session.put("status", status);
        session.put("totalSteps", 5);
        session.put("completedSteps", 0);
        session.put("failedSteps", 0);
        session.put("lastMessage", "Page Assistant session is ready");
        session.put("createdAt", now);
        session.put("updatedAt", now);
        session.put("steps", List.of());
        return session;
    }

    private boolean aiCodingAccessEnabled(Map<String, Object> project) {
        Object value = project.get("aiCodingAccess");
        if (value instanceof Map<?, ?> map) {
            return booleanValue(map.get("enabled"));
        }
        return false;
    }

    private boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private String nullToEmpty(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private Long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : Long.valueOf(String.valueOf(value));
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private record RuntimeObject(boolean created, Map<String, Object> body) {
        static RuntimeObject empty(boolean created, Map<String, Object> body) {
            return new RuntimeObject(created, body);
        }

        String id() {
            return body == null ? null : String.valueOf(body.get("id"));
        }
    }

    public record AiCodingGatewayManifest(String schema,
                                          AiCodingProject project,
                                          AiCodingAuth auth,
                                          AiCodingGatewayEndpoints endpoints,
                                          ContextCandidateSubmission contextCandidateSubmission,
                                          List<AiCodingCapability> capabilities) {
    }

    public record AiCodingProject(Long id,
                                  String projectCode,
                                  String name,
                                  String projectKind,
                                  String environment) {
    }

    public record AiCodingAuth(String headerName, String auditActor, List<String> guidance) {
    }

    public record AiCodingGatewayEndpoints(String manifestUrl,
                                           String contextCandidatesUrl,
                                           String contextCandidatesBatchUrl,
                                           String contextCandidateStatusUrlTemplate,
                                           String sdkAccessManifestUrl,
                                           String sdkAccessSessionUrl,
                                           String sdkAccessLatestSessionUrl,
                                           String pageAssistantManifestUrl,
                                           String pageAssistantSessionUrl,
                                           String workflowCreateUrl,
                                           String workflowContextUrlTemplate,
                                           String workflowPatchUrlTemplate,
                                           String workflowValidateUrlTemplate,
                                           String workflowRunUrlTemplate,
                                           String workflowVersionsUrlTemplate,
                                           String workflowPublishUrlTemplate,
                                           String workflowRunsUrlTemplate,
                                           String contextCandidateReviewUrl,
                                           String contextCandidateAuditUrlTemplate) {
    }

    public record ContextCandidateSubmission(String schema,
                                             String endpoint,
                                             String batchEndpoint,
                                             String reviewMode,
                                             String memoryLane,
                                             String tenantId,
                                             String defaultSourceType,
                                             String defaultCandidateType,
                                             List<String> requiredFields,
                                             List<String> candidateTypes,
                                             List<String> sourceTypes,
                                             TraceMetadata traceMetadata,
                                             List<String> serverControlledFields,
                                             List<String> guidance) {
    }

    public record TraceMetadata(String metadataKey,
                                String generatedSubmissionIdPrefix,
                                String defaultOrigin,
                                String traceIdPolicy,
                                String sessionIdPolicy) {
    }

    public record AiCodingCapability(String key,
                                     String title,
                                     String targetType,
                                     String entryUrl,
                                     List<String> guidance) {
    }
}
