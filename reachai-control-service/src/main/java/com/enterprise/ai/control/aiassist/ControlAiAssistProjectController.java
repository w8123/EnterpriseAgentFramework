package com.enterprise.ai.control.aiassist;

import com.enterprise.ai.control.client.capability.CapabilityProjectOnboardingClient;
import feign.FeignException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ai-assist/projects/{projectId}")
@RequiredArgsConstructor
public class ControlAiAssistProjectController {

    private static final String SECRET_ENV_NAME = "REACHAI_REGISTRY_APP_SECRET";
    private static final String WORKFLOW_AI_CODING_SKILL_NAME = "workflow-ai-coding";
    private static final String ONBOARDING_SKILL_NAME = "reachai-onboarding";

    private final CapabilityProjectOnboardingClient capabilityClient;

    static List<SdkArtifact> sdkArtifacts() {
        return List.of(
                new SdkArtifact(
                        "maven",
                        "java",
                        "com.enterprise.ai:reachai-capability-sdk:1.0.0-SNAPSHOT",
                        "com.enterprise.ai",
                        "reachai-capability-sdk",
                        null,
                        "1.0.0-SNAPSHOT",
                        "corporate-maven-or-local-install",
                        List.of(),
                        "mvn -pl reachai-spring-boot2-starter -am install -DskipTests",
                        "Resolve from the corporate Maven repository, a published ReachAI Maven repository, or local Maven install. The ReachAI platform baseUrl is not a Maven repository."),
                new SdkArtifact(
                        "maven",
                        "java",
                        "com.enterprise.ai:reachai-spring-boot2-starter:1.0.0-SNAPSHOT",
                        "com.enterprise.ai",
                        "reachai-spring-boot2-starter",
                        null,
                        "1.0.0-SNAPSHOT",
                        "corporate-maven-or-local-install",
                        List.of(),
                        "mvn -pl reachai-spring-boot2-starter -am install -DskipTests",
                        "Install the starter into the local Maven repository when no published repository is configured."),
                new SdkArtifact(
                        "npm",
                        "browser",
                        "@reachai/embed-chat@1.0.0-SNAPSHOT",
                        null,
                        null,
                        "@reachai/embed-chat",
                        "1.0.0-SNAPSHOT",
                        "npm-or-local-sdk-build",
                        List.of(),
                        "cd ai-admin-front && npm run build:sdk",
                        "Use the published npm package when available, or the local SDK build artifact produced by the ReachAI frontend package."));
    }

    static Map<String, ResponseShape> responseShapes() {
        Map<String, ResponseShape> shapes = new LinkedHashMap<>();
        shapes.put("embed", new ResponseShape(
                "ApiResult",
                Map.of(
                        "token", "data.token",
                        "expiresIn", "data.expiresIn",
                        "sessionId", "data.sessionId",
                        "answer", "data.answer",
                        "pageActionQueue", "data.metadata.pageActionQueue"),
                "Embed token/session/message/page-action APIs return ApiResult; top-level message is transport status only."));
        shapes.put("agentProvisioning", new ResponseShape(
                "bare-json",
                Map.of(
                        "agentKeySlug", "agent.keySlug",
                        "supervisorConfigId", "supervisorConfig.id",
                        "supervisorConfigStatus", "supervisorConfig.status",
                        "createdSupervisorConfig", "createdSupervisorConfig"),
                "POST /api/ai-coding/projects/{projectId}/agents/provision is not ApiResult-wrapped."));
        shapes.put("aiAccessSessions", new ResponseShape(
                "bare-json",
                Map.of(
                        "sessionId", "sessionId",
                        "status", "status",
                        "steps", "steps"),
                "AI access session endpoints return the session object at the top level."));
        shapes.put("sdkAccessCheck", new ResponseShape(
                "bare-json",
                Map.of(
                        "overallStatus", "overallStatus",
                        "checks", "checks",
                        "readiness", "readiness"),
                "SDK access check is a platform-console response and is not ApiResult-wrapped."));
        shapes.put("onboardingManifest", new ResponseShape(
                "bare-json",
                Map.of(
                        "project", "project",
                        "sdkArtifacts", "sdkArtifacts",
                        "responseShapes", "responseShapes",
                        "agentProvisioning", "agentProvisioning"),
                "Manifest responses are top-level JSON contracts for AI coding tools."));
        return shapes;
    }

    @GetMapping("/onboarding-manifest")
    public ResponseEntity<OnboardingManifestResponse> onboardingManifest(@PathVariable Long projectId,
                                                                         HttpServletRequest request) {
        try {
            Map<String, Object> project = capabilityClient.getOnboardingProjectById(projectId);
            String baseUrl = ControlAiAssistSkillController.requestBaseUrl(request);
            String projectApiRoot = baseUrl + "/api/ai-assist/projects/" + projectId;
            return ResponseEntity.ok(buildManifest(project, baseUrl, projectApiRoot));
        } catch (FeignException.NotFound ex) {
            return ResponseEntity.notFound().build();
        }
    }

    @PatchMapping("/ai-coding-access")
    public ResponseEntity<AiCodingAccessManifest> updateAiCodingAccess(
            @PathVariable Long projectId,
            @RequestBody(required = false) AiCodingAccessUpdateRequest request) {
        try {
            Map<String, Object> body = capabilityClient.updateAiCodingAccess(projectId, request);
            return ResponseEntity.ok(toAiCodingAccess(body));
        } catch (FeignException.NotFound ex) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/access-sessions")
    public ResponseEntity<AiAccessSessionView> startAccessSession(
            @PathVariable Long projectId,
            @RequestParam(required = false) String toolName) {
        try {
            Map<String, Object> project = capabilityClient.getOnboardingProjectById(projectId);
            return ResponseEntity.ok(sessionView(project, toolName, "OPEN", null));
        } catch (FeignException.NotFound ex) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/access-sessions/latest")
    public ResponseEntity<AiAccessSessionView> latestAccessSession(@PathVariable Long projectId) {
        try {
            Map<String, Object> project = capabilityClient.getOnboardingProjectById(projectId);
            return ResponseEntity.ok(sessionView(project, null, "OPEN", null));
        } catch (FeignException.NotFound ex) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/access-sessions/{sessionId}/checks/run")
    public ResponseEntity<AiAccessCheckRunResponse> runAccessSessionChecks(
            @PathVariable Long projectId,
            @PathVariable String sessionId,
            @RequestBody(required = false) Map<String, Object> request) {
        try {
            Map<String, Object> project = capabilityClient.getOnboardingProjectById(projectId);
            SdkAccessCheckResponse checkResult = sdkAccessCheck(project);
            AiAccessSessionView session = sessionView(project, null, checkResult.overallStatus(), sessionId);
            return ResponseEntity.ok(new AiAccessCheckRunResponse(checkResult, session));
        } catch (FeignException.NotFound ex) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/page-assistant/onboarding-manifest")
    public ResponseEntity<PageAssistantOnboardingManifestResponse> pageAssistantOnboardingManifest(
            @PathVariable Long projectId,
            @RequestParam(required = false) String toolName,
            @RequestParam(required = false) String pageKey,
            @RequestParam(required = false) String routePattern,
            @RequestParam(required = false) List<String> actionKeys,
            HttpServletRequest request) {
        try {
            Map<String, Object> project = capabilityClient.getOnboardingProjectById(projectId);
            String baseUrl = ControlAiAssistSkillController.requestBaseUrl(request);
            AiAccessSessionView session = pageAssistantSessionView(
                    project,
                    toolName,
                    pageKey,
                    routePattern,
                    "OPEN",
                    null);
            return ResponseEntity.ok(pageAssistantManifest(project, baseUrl, session, actionKeys));
        } catch (FeignException.NotFound ex) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/page-assistant/sessions")
    public ResponseEntity<AiAccessSessionView> startPageAssistantSession(
            @PathVariable Long projectId,
            @RequestBody(required = false) PageAssistantSessionRequest request) {
        try {
            Map<String, Object> project = capabilityClient.getOnboardingProjectById(projectId);
            return ResponseEntity.ok(pageAssistantSessionView(
                    project,
                    request == null ? null : request.toolName(),
                    request == null ? null : request.pageKey(),
                    request == null ? null : request.routePattern(),
                    "OPEN",
                    null));
        } catch (FeignException.NotFound ex) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/page-assistant/sessions/latest")
    public ResponseEntity<AiAccessSessionView> latestPageAssistantSession(
            @PathVariable Long projectId,
            @RequestParam(required = false) String pageKey) {
        try {
            Map<String, Object> project = capabilityClient.getOnboardingProjectById(projectId);
            return ResponseEntity.ok(pageAssistantSessionView(project, null, pageKey, null, "OPEN", null));
        } catch (FeignException.NotFound ex) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/page-assistant/sessions")
    public ResponseEntity<List<PageAssistantSessionSummary>> pageAssistantSessions(
            @PathVariable Long projectId,
            @RequestParam(required = false) String pageKey) {
        try {
            Map<String, Object> project = capabilityClient.getOnboardingProjectById(projectId);
            AiAccessSessionView session = pageAssistantSessionView(project, null, pageKey, null, "OPEN", null);
            return ResponseEntity.ok(List.of(toPageAssistantSessionSummary(session, 0)));
        } catch (FeignException.NotFound ex) {
            return ResponseEntity.notFound().build();
        }
    }

    @PutMapping("/page-assistant/sessions/{sessionId}/target")
    public ResponseEntity<AiAccessSessionView> bindPageAssistantTarget(
            @PathVariable Long projectId,
            @PathVariable String sessionId,
            @RequestBody(required = false) PageAssistantTargetRequest request) {
        try {
            Map<String, Object> project = capabilityClient.getOnboardingProjectById(projectId);
            return ResponseEntity.ok(pageAssistantSessionView(
                    project,
                    null,
                    request == null ? null : request.pageKey(),
                    request == null ? null : request.routePattern(),
                    "OPEN",
                    sessionId));
        } catch (FeignException.NotFound ex) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/page-assistant/sessions/{sessionId}/catalog/sync")
    public ResponseEntity<PageAssistantCatalogSyncResponse> syncPageAssistantCatalog(
            @PathVariable Long projectId,
            @PathVariable String sessionId,
            @RequestBody(required = false) PageAssistantCatalogSyncRequest request) {
        try {
            Map<String, Object> project = capabilityClient.getOnboardingProjectById(projectId);
            String pageKey = request == null ? null : request.pageKey();
            String routePattern = request == null ? null : request.routePattern();
            AiAccessSessionView session = pageAssistantSessionView(project, null, pageKey, routePattern, "PASS", sessionId);
            int actionCount = request == null || request.actions() == null ? 0 : request.actions().size();
            return ResponseEntity.ok(new PageAssistantCatalogSyncResponse(
                    stringValue(project.get("projectCode")),
                    stringValue(project.get("projectCode")),
                    pageKey,
                    actionCount,
                    session));
        } catch (FeignException.NotFound ex) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/page-assistant/sessions/{sessionId}/checks/run")
    public ResponseEntity<PageAssistantCheckRunResponse> runPageAssistantChecks(
            @PathVariable Long projectId,
            @PathVariable String sessionId,
            @RequestBody(required = false) Map<String, Object> request) {
        try {
            Map<String, Object> project = capabilityClient.getOnboardingProjectById(projectId);
            String pageKey = stringValue(request == null ? null : request.get("pageKey"));
            String routePattern = stringValue(request == null ? null : request.get("routePattern"));
            PageAssistantCheckResponse checkResult = pageAssistantCheck(project, pageKey, routePattern);
            AiAccessSessionView session = pageAssistantSessionView(project, null, pageKey, routePattern,
                    checkResult.overallStatus(), sessionId);
            return ResponseEntity.ok(new PageAssistantCheckRunResponse(checkResult, session));
        } catch (FeignException.NotFound ex) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/page-assistant/pages/register")
    public ResponseEntity<PageAssistantPageRegisterResponse> registerPageAssistantPage(
            @PathVariable Long projectId,
            @RequestBody(required = false) PageAssistantPageRegisterRequest request) {
        try {
            Map<String, Object> project = capabilityClient.getOnboardingProjectById(projectId);
            String sessionId = request == null ? null : request.sessionId();
            String pageKey = request == null ? null : request.pageKey();
            String routePattern = request == null ? null : request.routePattern();
            AiAccessSessionView session = pageAssistantSessionView(project, request == null ? null : request.toolName(),
                    pageKey, routePattern, "PASS", sessionId);
            List<String> registeredActions = request == null || request.actions() == null
                    ? List.of()
                    : request.actions().stream().map(PageAssistantCatalogActionRequest::actionKey).toList();
            return ResponseEntity.ok(new PageAssistantPageRegisterResponse(
                    session,
                    pageAssistantCheck(project, pageKey, routePattern),
                    new RegisteredPage(
                            stringValue(project.get("projectCode")),
                            stringValue(project.get("projectCode")),
                            pageKey,
                            request == null ? null : request.pageName(),
                            routePattern,
                            request == null ? null : request.framework(),
                            request == null ? null : request.bridgeGlobal()),
                    registeredActions,
                    request == null || request.files() == null ? List.of() : request.files()));
        } catch (FeignException.NotFound ex) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/page-assistant/sessions/{sessionId}/workflow-ai-coding-result")
    public ResponseEntity<AiAccessSessionView> reportPageAssistantWorkflowAiCodingResult(
            @PathVariable Long projectId,
            @PathVariable String sessionId,
            @RequestBody(required = false) Map<String, Object> request) {
        return pageAssistantWorkflowAiCodingSession(projectId, sessionId, request, "PASS");
    }

    @DeleteMapping("/page-assistant/sessions/{sessionId}/workflow-ai-coding-result")
    public ResponseEntity<AiAccessSessionView> resetPageAssistantWorkflowAiCodingResult(
            @PathVariable Long projectId,
            @PathVariable String sessionId) {
        return pageAssistantWorkflowAiCodingSession(projectId, sessionId, Map.of(), "OPEN");
    }

    private OnboardingManifestResponse buildManifest(Map<String, Object> project,
                                                     String baseUrl,
                                                     String projectApiRoot) {
        ProjectManifest projectManifest = new ProjectManifest(
                longValue(project.get("id")),
                stringValue(project.get("name")),
                stringValue(project.get("projectCode")),
                stringValue(project.get("projectKind")),
                stringValue(project.get("environment")),
                stringValue(project.get("baseUrl")),
                emptyToNull(stringValue(project.get("contextPath"))),
                stringValue(project.get("registryAppKey")),
                booleanValue(project.get("registryCredentialConfigured")));
        AiCodingAccessManifest aiCodingAccess = toAiCodingAccess(mapValue(project.get("aiCodingAccess")));
        EmbedManifest embed = new EmbedManifest("/api/reachai/embed-token", null, null, List.of());
        AgentProvisioningManifest provisioning = buildAgentProvisioningManifest(projectManifest, projectApiRoot);
        return new OnboardingManifestResponse(
                "reachai.onboarding.v2",
                projectManifest,
                aiCodingAccess,
                new SdkManifest(
                        "1.0.0-SNAPSHOT",
                        List.of(
                                new MavenDependency("com.enterprise.ai", "reachai-capability-sdk", "1.0.0-SNAPSHOT"),
                                new MavenDependency("com.enterprise.ai", "reachai-spring-boot2-starter", "1.0.0-SNAPSHOT")
                        ),
                        new ReachAiConfigManifest(
                                baseUrl,
                                projectManifest.registryAppKey(),
                                SECRET_ENV_NAME,
                                projectManifest.projectCode(),
                                projectManifest.name(),
                                projectManifest.baseUrl(),
                                projectManifest.contextPath(),
                                projectManifest.environment())),
                sdkArtifacts(),
                responseShapes(),
                new PlatformEndpoints(
                        baseUrl + "/api/ai-assist/skills/" + ONBOARDING_SKILL_NAME + "/latest.zip",
                        projectApiRoot + "/onboarding-manifest",
                        baseUrl + "/api/scan-projects/" + projectManifest.id() + "/sdk-access-check",
                        baseUrl + "/api/scan-projects/" + projectManifest.id() + "/tools/reconcile"),
                embed,
                provisioning,
                buildAgentSupervisorManifest(projectManifest, provisioning, baseUrl),
                new SecurityGuidance(
                        SECRET_ENV_NAME,
                        "Do not paste or write the registry app secret into AI chat context. Store it in a local environment variable or secret manager."));
    }

    private PageAssistantOnboardingManifestResponse pageAssistantManifest(Map<String, Object> project,
                                                                          String baseUrl,
                                                                          AiAccessSessionView session,
                                                                          List<String> actionKeys) {
        Long projectId = longValue(project.get("id"));
        String controlRoot = baseUrl + "/api/ai-assist/projects/" + projectId + "/page-assistant";
        String externalRoot = baseUrl + "/api/ai-coding/projects/" + projectId + "/page-assistant";
        ProjectManifest projectManifest = new ProjectManifest(
                projectId,
                stringValue(project.get("name")),
                stringValue(project.get("projectCode")),
                stringValue(project.get("projectKind")),
                stringValue(project.get("environment")),
                stringValue(project.get("baseUrl")),
                emptyToNull(stringValue(project.get("contextPath"))),
                stringValue(project.get("registryAppKey")),
                booleanValue(project.get("registryCredentialConfigured")));
        List<String> normalizedActionKeys = actionKeys == null ? List.of() : actionKeys.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .toList();
        String sessionUrl = controlRoot + "/sessions/" + session.sessionId();
        return new PageAssistantOnboardingManifestResponse(
                "reachai.page-assistant.onboarding.v1",
                projectManifest,
                toAiCodingAccess(mapValue(project.get("aiCodingAccess"))),
                new PageAssistantAuth(
                        "ai-coding-key",
                        "X-ReachAI-AiCoding-Key",
                        "REACHAI_AI_CODING_KEY",
                        externalRoot + "/**",
                        controlRoot + "/**",
                        List.of("Use X-ReachAI-AiCoding-Key for external AI Coding tools.",
                                "Use the control path from the console with platform login.")),
                new PageAssistantTarget(session.targetPageKey(), session.targetRoute(), normalizedActionKeys),
                session,
                new PageAssistantEndpoints(
                        controlRoot + "/onboarding-manifest",
                        controlRoot + "/sessions/latest",
                        sessionUrl + "/steps/{stepKey}/report",
                        sessionUrl + "/target",
                        sessionUrl + "/catalog/sync",
                        sessionUrl + "/checks/run",
                        controlRoot + "/pages/register",
                        baseUrl + "/api/ai-assist/skills/reachai-page-assistant-onboarding/latest.zip",
                        baseUrl + "/api/ai-assist/skills/reachai-page-assistant-onboarding/scripts/reachai-page-assistant.ps1"),
                new SecurityGuidance(SECRET_ENV_NAME,
                        "Do not store aiCodingKey or registry secrets in browser runtime code."),
                new LocalExecution(true, "Page Assistant scaffolding and verification run in the business frontend repository."),
                pageActionContract(),
                new PageAssistantScaffold(
                        "angular",
                        List.of(
                                new PageAssistantTemplate("bridge", "page-bridge-runtime"),
                                new PageAssistantTemplate("catalog", "page-action-catalog")),
                        "scripts/reachai-page-assistant.ps1",
                        baseUrl + "/api/ai-assist/skills/reachai-page-assistant-onboarding/scripts/reachai-page-assistant.ps1",
                        baseUrl + "/api/ai-assist/skills/reachai-page-assistant-onboarding/latest.zip",
                        ".\\scripts\\reachai-page-assistant.ps1 scaffold -ManifestUrl \"" + controlRoot + "/onboarding-manifest\" -AiCodingKey $env:REACHAI_AI_CODING_KEY -Framework angular -OutputDir \".\\src\\app\\shared\\reachai\"",
                        ".\\scripts\\reachai-page-assistant.ps1 verify -ManifestUrl \"" + controlRoot + "/onboarding-manifest\" -AiCodingKey $env:REACHAI_AI_CODING_KEY -FrontendUrl \"<业务前端地址>\""));
    }

    private PageActionContract pageActionContract() {
        return new PageActionContract(
                "__REACHAI_PAGE_BRIDGE__",
                "reachai.page-bridge.v1",
                List.of("angular", "vue", "react"),
                List.of("readPageState", "readTable", "setFilters"),
                new PageActionSafety(true, true),
                null);
    }

    private AgentProvisioningManifest buildAgentProvisioningManifest(ProjectManifest project, String projectApiRoot) {
        String defaultKeySlug = pageCopilotKeySlug(project.projectCode(), project.id());
        return new AgentProvisioningManifest(
                "agent-provisioning.v2",
                defaultKeySlug,
                projectApiRoot.replace("/api/ai-assist/", "/api/ai-coding/") + "/agents/provision",
                true,
                true,
                true,
                "REQUESTED_OR_FIRST_ACTIVE_LLM",
                List.of(
                        "Call provisionAgentUrl before wiring embedded chat.",
                        "Use response.agent.keySlug as the business frontend agentId.",
                        "Provisioning publishes an ACTIVE AgentScope Supervisor config without creating a placeholder Workflow.",
                        "Do not ask the user to manually create or choose a project Agent during SDK onboarding."));
    }

    private AgentSupervisorManifest buildAgentSupervisorManifest(ProjectManifest project,
                                                                 AgentProvisioningManifest provisioning,
                                                                 String baseUrl) {
        String globalAgentKeySlug = provisioning.defaultKeySlug();
        return new AgentSupervisorManifest(
                "agent-supervisor.workflow-tools.v1",
                globalAgentKeySlug,
                "AGENTSCOPE",
                "WORKFLOW_AS_TOOL_ALLOW_LIST",
                new AgentSupervisorEndpoints(
                        baseUrl + "/api/agents",
                        baseUrl + "/api/agents/{agentId}/config-versions",
                        baseUrl + "/api/agents/{agentId}/config-versions/draft",
                        baseUrl + "/api/workflows/{workflowId}/page-assistant/attach-tool",
                        baseUrl + "/api/runtime/agents/execute"),
                new WorkflowAiCodingManifest(
                        baseUrl + "/api/ai-assist/skills/" + WORKFLOW_AI_CODING_SKILL_NAME + "/latest.zip",
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
                                "All Workflow AI Coding endpoints require project aiCodingKey via header X-ReachAI-AiCoding-Key; manage the key in project detail.",
                                "Read /context before patch; use workflow.updatedAt as baseRevision when saving.",
                                "After the first valid workflow draft is saved, call /publish once to create the initial ACTIVE workflow version.")),
                List.of(
                        "Provision or reuse one project-level page copilot Agent entry.",
                        "Store every executable graph as a runtime_workflow and publish an ACTIVE version before attachment.",
                        "Attach published Workflows to the Agent Supervisor Workflow-as-Tool allow-list.",
                        "Publish a new Agent config version after changing its tool catalog.",
                        "Use only the published Agent config Workflow-as-Tool catalog for runtime selection."));
    }

    private String pageCopilotKeySlug(String projectCode, Long projectId) {
        String source = StringUtils.hasText(projectCode) ? projectCode.trim() : "project-" + projectId;
        return source.toLowerCase()
                .replaceAll("[^a-z0-9_-]+", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "") + "-page-copilot";
    }

    private AiCodingAccessManifest toAiCodingAccess(Map<String, Object> body) {
        return new AiCodingAccessManifest(
                booleanValue(body.get("enabled")),
                stringValue(body.get("accessKey")));
    }

    private AiAccessSessionView sessionView(Map<String, Object> project,
                                            String toolName,
                                            String status,
                                            String requestedSessionId) {
        Long projectId = longValue(project.get("id"));
        String now = Instant.now().toString();
        String sessionId = StringUtils.hasText(requestedSessionId) ? requestedSessionId : "sdk-access-" + projectId;
        List<AiAccessStepView> steps = sdkAccessSteps(status);
        long completed = steps.stream().filter(step -> "PASS".equals(step.status())).count();
        long failed = steps.stream().filter(step -> "FAIL".equals(step.status())).count();
        return new AiAccessSessionView(
                sessionId,
                projectId,
                stringValue(project.get("projectCode")),
                StringUtils.hasText(toolName) ? toolName : null,
                "SDK_ACCESS",
                null,
                null,
                status,
                steps.size(),
                (int) completed,
                (int) failed,
                "PASS".equals(status) ? "SDK access check passed" : "SDK access session is ready",
                now,
                now,
                steps);
    }

    private AiAccessSessionView pageAssistantSessionView(Map<String, Object> project,
                                                         String toolName,
                                                         String pageKey,
                                                         String routePattern,
                                                         String status,
                                                         String requestedSessionId) {
        Long projectId = longValue(project.get("id"));
        String normalizedStatus = StringUtils.hasText(status) ? status : "OPEN";
        String normalizedPageKey = StringUtils.hasText(pageKey) ? pageKey.trim() : null;
        String now = Instant.now().toString();
        String sessionId = StringUtils.hasText(requestedSessionId)
                ? requestedSessionId
                : "page-assistant-" + projectId + (normalizedPageKey == null ? "" : "-" + normalizedPageKey);
        List<AiAccessStepView> steps = pageAssistantSteps(normalizedStatus);
        long completed = steps.stream().filter(step -> "PASS".equals(step.status())).count();
        long failed = steps.stream().filter(step -> "FAIL".equals(step.status())).count();
        return new AiAccessSessionView(
                sessionId,
                projectId,
                stringValue(project.get("projectCode")),
                StringUtils.hasText(toolName) ? toolName : null,
                "PAGE_ASSISTANT",
                normalizedPageKey,
                StringUtils.hasText(routePattern) ? routePattern.trim() : null,
                normalizedStatus,
                steps.size(),
                (int) completed,
                (int) failed,
                "PASS".equals(normalizedStatus) ? "Page Assistant checks passed" : "Page Assistant session is ready",
                now,
                now,
                steps);
    }

    private List<AiAccessStepView> sdkAccessSteps(String status) {
        boolean passed = "PASS".equals(status);
        String stepStatus = passed ? "PASS" : "TODO";
        List<AiAccessStepView> steps = new ArrayList<>();
        steps.add(step("PROJECT", "项目识别", stepStatus));
        steps.add(step("STARTER", "后端 Starter", stepStatus));
        steps.add(step("GATEWAY", "网关路由", stepStatus));
        steps.add(step("BUSINESS_API", "业务服务校验", stepStatus));
        steps.add(step("EMBED_TOKEN", "前端 Embed Token", stepStatus));
        steps.add(step("FINAL_CHECK", "最终自检", stepStatus));
        return steps;
    }

    private List<AiAccessStepView> pageAssistantSteps(String status) {
        boolean passed = "PASS".equals(status);
        String stepStatus = passed ? "PASS" : "TODO";
        List<AiAccessStepView> steps = new ArrayList<>();
        steps.add(step("TARGET_PAGE", "目标页面", stepStatus));
        steps.add(step("BRIDGE_SCAFFOLD", "页面 Bridge", stepStatus));
        steps.add(step("ACTION_CATALOG", "动作目录", stepStatus));
        steps.add(step("SELF_CHECK", "page-assistant validate", stepStatus));
        steps.add(step("WORKFLOW_AI_CODING_DRAFT", "Workflow AI Coding", "TODO"));
        return steps;
    }

    private AiAccessStepView step(String key, String title, String status) {
        return new AiAccessStepView(key, title, status, null, List.of(), Map.of(), null, null, null, Instant.now().toString());
    }

    private SdkAccessCheckResponse sdkAccessCheck(Map<String, Object> project) {
        boolean credentialConfigured = booleanValue(project.get("registryCredentialConfigured"));
        AiCodingAccessManifest aiCodingAccess = toAiCodingAccess(mapValue(project.get("aiCodingAccess")));
        SdkAccessCheckItem sdkSyncCallbackCheck = sdkSyncCallbackCheck(project);
        List<SdkAccessCheckItem> checks = List.of(
                new SdkAccessCheckItem(
                        "PROJECT",
                        "项目识别",
                        "PASS",
                        "已读取项目 " + stringValue(project.get("projectCode")),
                        null),
                new SdkAccessCheckItem(
                        "REGISTRY_CREDENTIAL",
                        "注册凭据",
                        credentialConfigured ? "PASS" : "WARN",
                        credentialConfigured ? "已配置 active registry credential" : "尚未配置 active registry credential",
                        null),
                new SdkAccessCheckItem(
                        "AI_CODING_ACCESS",
                        "AI Coding 接入",
                        aiCodingAccess.enabled() ? "PASS" : "WARN",
                        aiCodingAccess.enabled() ? "已启用 AI Coding 接入" : "AI Coding 接入未启用",
                        null),
                sdkSyncCallbackCheck);
        String overall = checks.stream().anyMatch(check -> "FAIL".equals(check.status()))
                ? "FAIL"
                : checks.stream().anyMatch(check -> "WARN".equals(check.status())) ? "WARN" : "PASS";
        return new SdkAccessCheckResponse(
                longValue(project.get("id")),
                stringValue(project.get("projectCode")),
                overall,
                List.of(
                        new SdkAccessReadiness("CODE_READY", "代码接入", overall, "SDK onboarding route is available"),
                        new SdkAccessReadiness("RUNTIME_READY", "Runtime 就绪", overall, "Runtime readiness requires SDK instance heartbeat"),
                        new SdkAccessReadiness("E2E_READY", "端到端", overall, "Verify embed/token broker flow; API calls are optional after API Management manual SDK sync")),
                checks);
    }

    static SdkAccessCheckItem sdkSyncCallbackCheck(Map<String, Object> project) {
        String baseUrl = project.get("baseUrl") == null ? null : String.valueOf(project.get("baseUrl"));
        if (!StringUtils.hasText(baseUrl)) {
            return new SdkAccessCheckItem(
                    "SDK_SYNC_CALLBACK",
                    "SDK 同步回调",
                    "WARN",
                    "尚未配置 ReachAI 服务端可访问的 reachai.project.base-url",
                    null);
        }
        String contextPath = project.get("contextPath") == null ? null : String.valueOf(project.get("contextPath"));
        String normalizedContextPath = StringUtils.hasText(contextPath) && !"/".equals(contextPath.trim())
                ? "/" + contextPath.trim().replaceAll("^/+|/+$", "")
                : "";
        String targetUrl = baseUrl.trim().replaceAll("/+$", "")
                + normalizedContextPath
                + "/reachai/registry/capabilities/sync";
        String lowerTarget = targetUrl.toLowerCase();
        boolean loopback = lowerTarget.startsWith("http://localhost")
                || lowerTarget.startsWith("https://localhost")
                || lowerTarget.startsWith("http://127.0.0.1")
                || lowerTarget.startsWith("https://127.0.0.1")
                || lowerTarget.startsWith("http://[::1]")
                || lowerTarget.startsWith("https://[::1]");
        return new SdkAccessCheckItem(
                "SDK_SYNC_CALLBACK",
                "SDK 同步回调",
                "PASS",
                loopback
                        ? "已计算同步回调目标；当前为回环地址，仅适用于 ReachAI 与业务系统同机或共享网络命名空间"
                        : "已计算同步回调目标；实际可达性和业务鉴权/CSRF需由 API 管理手动同步验证",
                targetUrl);
    }

    private PageAssistantCheckResponse pageAssistantCheck(Map<String, Object> project,
                                                          String pageKey,
                                                          String routePattern) {
        boolean targetReady = StringUtils.hasText(pageKey) || StringUtils.hasText(routePattern);
        List<PageAssistantCheckItem> checks = List.of(
                new PageAssistantCheckItem(
                        "PROJECT",
                        "项目识别",
                        "PASS",
                        "已读取项目 " + stringValue(project.get("projectCode")),
                        null),
                new PageAssistantCheckItem(
                        "TARGET_PAGE",
                        "目标页面",
                        targetReady ? "PASS" : "WARN",
                        targetReady ? "已绑定页面目标" : "尚未选择 pageKey 或 routePattern",
                        null),
                new PageAssistantCheckItem(
                        "AI_CODING_ACCESS",
                        "AI Coding 接入",
                        toAiCodingAccess(mapValue(project.get("aiCodingAccess"))).enabled() ? "PASS" : "WARN",
                        toAiCodingAccess(mapValue(project.get("aiCodingAccess"))).enabled()
                                ? "已启用 AI Coding 接入"
                                : "AI Coding 接入未启用",
                        null));
        String overall = checks.stream().anyMatch(check -> "FAIL".equals(check.status()))
                ? "FAIL"
                : checks.stream().anyMatch(check -> "WARN".equals(check.status())) ? "WARN" : "PASS";
        return new PageAssistantCheckResponse(
                longValue(project.get("id")),
                stringValue(project.get("projectCode")),
                StringUtils.hasText(pageKey) ? pageKey.trim() : null,
                StringUtils.hasText(routePattern) ? routePattern.trim() : null,
                overall,
                checks);
    }

    private PageAssistantSessionSummary toPageAssistantSessionSummary(AiAccessSessionView session, int actionCount) {
        return new PageAssistantSessionSummary(
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

    private ResponseEntity<AiAccessSessionView> pageAssistantWorkflowAiCodingSession(Long projectId,
                                                                                     String sessionId,
                                                                                     Map<String, Object> request,
                                                                                     String status) {
        try {
            Map<String, Object> project = capabilityClient.getOnboardingProjectById(projectId);
            String pageKey = stringValue(request == null ? null : request.get("pageKey"));
            String routePattern = stringValue(request == null ? null : request.get("routePattern"));
            return ResponseEntity.ok(pageAssistantSessionView(project, null, pageKey, routePattern, status, sessionId));
        } catch (FeignException.NotFound ex) {
            return ResponseEntity.notFound().build();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private Long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : Long.valueOf(String.valueOf(value));
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private String emptyToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    public record AiCodingAccessUpdateRequest(Boolean enabled, String accessKey) {
    }

    public record OnboardingManifestResponse(
            String schema,
            ProjectManifest project,
            AiCodingAccessManifest aiCodingAccess,
            SdkManifest sdk,
            List<SdkArtifact> sdkArtifacts,
            Map<String, ResponseShape> responseShapes,
            PlatformEndpoints endpoints,
            EmbedManifest embed,
            AgentProvisioningManifest agentProvisioning,
            AgentSupervisorManifest agentSupervisor,
            SecurityGuidance security
    ) {
    }

    public record ProjectManifest(
            Long id,
            String name,
            String projectCode,
            String projectKind,
            String environment,
            String baseUrl,
            String contextPath,
            String registryAppKey,
            boolean registryCredentialConfigured
    ) {
    }

    public record AiCodingAccessManifest(boolean enabled, String accessKey) {
    }

    public record AiAccessStepView(
            String stepKey,
            String title,
            String status,
            String message,
            List<String> files,
            Map<String, Object> evidence,
            String reportedBy,
            String startedAt,
            String completedAt,
            String updatedAt
    ) {
    }

    public record AiAccessSessionView(
            String sessionId,
            Long projectId,
            String projectCode,
            String toolName,
            String scenario,
            String targetPageKey,
            String targetRoute,
            String status,
            int totalSteps,
            int completedSteps,
            int failedSteps,
            String lastMessage,
            String createdAt,
            String updatedAt,
            List<AiAccessStepView> steps
    ) {
    }

    public record AiAccessCheckRunResponse(SdkAccessCheckResponse checkResult, AiAccessSessionView session) {
    }

    public record PageAssistantSessionRequest(String toolName,
                                              String pageKey,
                                              String routePattern,
                                              List<String> actionKeys) {
    }

    public record PageAssistantTargetRequest(String pageKey,
                                             String routePattern,
                                             List<String> actionKeys) {
    }

    public record PageAssistantCatalogActionRequest(String actionKey,
                                                    String title,
                                                    String description,
                                                    Boolean confirmRequired,
                                                    Map<String, Object> inputSchema,
                                                    Map<String, Object> outputSchema,
                                                    Map<String, Object> sampleArgs,
                                                    List<String> allowedAgentIds,
                                                    Map<String, Object> metadata) {
    }

    public record PageAssistantCatalogSyncRequest(String pageKey,
                                                  String name,
                                                  String routePattern,
                                                  String origin,
                                                  String pageInstanceId,
                                                  Boolean replaceActions,
                                                  List<PageAssistantCatalogActionRequest> actions,
                                                  Map<String, Object> metadata) {
    }

    public record PageAssistantFileEvidence(String path,
                                            String role,
                                            Boolean exists,
                                            String sha256,
                                            String validationStatus,
                                            String validationMessage) {
    }

    public record PageAssistantPageRegisterRequest(String sessionId,
                                                   String toolName,
                                                   String pageKey,
                                                   String pageName,
                                                   String routePattern,
                                                   String framework,
                                                   String frameworkVersion,
                                                   String bridgeGlobal,
                                                   Boolean replaceActions,
                                                   List<PageAssistantFileEvidence> files,
                                                   List<PageAssistantCatalogActionRequest> actions,
                                                   Map<String, Object> verification,
                                                   String handoffSummary) {
    }

    public record PageAssistantOnboardingManifestResponse(String schema,
                                                          ProjectManifest project,
                                                          AiCodingAccessManifest aiCodingAccess,
                                                          PageAssistantAuth auth,
                                                          PageAssistantTarget target,
                                                          AiAccessSessionView session,
                                                          PageAssistantEndpoints endpoints,
                                                          SecurityGuidance security,
                                                          LocalExecution localExecution,
                                                          PageActionContract pageActionContract,
                                                          PageAssistantScaffold scaffold) {
    }

    public record PageAssistantAuth(String mode,
                                    String headerName,
                                    String keyEnv,
                                    String externalToolPath,
                                    String platformSessionPath,
                                    List<String> guidance) {
    }

    public record PageAssistantTarget(String pageKey,
                                      String routePattern,
                                      List<String> actionKeys) {
    }

    public record PageAssistantEndpoints(String manifestUrl,
                                         String latestSessionUrl,
                                         String stepReportUrl,
                                         String targetBindUrl,
                                         String catalogSyncUrl,
                                         String checksRunUrl,
                                         String registerPageUrl,
                                         String skillPackageUrl,
                                         String scriptDownloadUrl) {
    }

    public record LocalExecution(boolean requiresLocalShell, String reason) {
    }

    public record PageActionContract(String bridgeGlobal,
                                     String protocolVersion,
                                     List<String> supportedFrameworks,
                                     List<String> recommendedActions,
                                     PageActionSafety safety,
                                     Object bridgeApi) {
    }

    public record PageActionSafety(boolean readonlyFirst, boolean highRiskActionsRequireConfirm) {
    }

    public record PageAssistantScaffold(String framework,
                                        List<PageAssistantTemplate> templates,
                                        String helperScriptPath,
                                        String scriptDownloadUrl,
                                        String skillPackageUrl,
                                        String scaffoldCommand,
                                        String verifyCommand) {
    }

    public record PageAssistantTemplate(String name, String role) {
    }

    public record PageAssistantCheckItem(String key,
                                         String label,
                                         String status,
                                         String message,
                                         String evidence) {
    }

    public record PageAssistantCheckResponse(Long projectId,
                                             String projectCode,
                                             String pageKey,
                                             String routePattern,
                                             String overallStatus,
                                             List<PageAssistantCheckItem> checks) {
    }

    public record PageAssistantCheckRunResponse(PageAssistantCheckResponse checkResult, AiAccessSessionView session) {
    }

    public record PageAssistantSessionSummary(String sessionId,
                                              Long projectId,
                                              String projectCode,
                                              String toolName,
                                              String targetPageKey,
                                              String targetRoute,
                                              String status,
                                              String completionState,
                                              int totalSteps,
                                              int completedSteps,
                                              int failedSteps,
                                              int actionCount,
                                              String lastMessage,
                                              String lastReportedAt,
                                              List<AiAccessStepView> steps) {
    }

    public record PageAssistantCatalogSyncResponse(String projectCode,
                                                   String appId,
                                                   String pageKey,
                                                   int actionCount,
                                                   AiAccessSessionView session) {
    }

    public record RegisteredPage(String projectCode,
                                 String appId,
                                 String pageKey,
                                 String pageName,
                                 String routePattern,
                                 String framework,
                                 String bridgeGlobal) {
    }

    public record PageAssistantPageRegisterResponse(AiAccessSessionView session,
                                                    PageAssistantCheckResponse checkResult,
                                                    RegisteredPage registeredPage,
                                                    List<String> registeredActions,
                                                    List<PageAssistantFileEvidence> fileEvidence) {
    }

    public record SdkAccessCheckResponse(
            Long projectId,
            String projectCode,
            String overallStatus,
            List<SdkAccessReadiness> readiness,
            List<SdkAccessCheckItem> checks
    ) {
    }

    public record SdkAccessReadiness(String key, String label, String status, String message) {
    }

    public record SdkAccessCheckItem(String key, String label, String status, String message, String evidence) {
    }

    public record SdkManifest(String version, List<MavenDependency> dependencies, ReachAiConfigManifest config) {
    }

    public record MavenDependency(String groupId, String artifactId, String version) {
    }

    public record SdkArtifact(String type,
                              String language,
                              String coordinates,
                              String groupId,
                              String artifactId,
                              String packageName,
                              String version,
                              String sourcePolicy,
                              List<String> repositoryUrls,
                              String localInstallCommand,
                              String notes) {
    }

    public record ResponseShape(String wrapper, Map<String, String> fields, String notes) {
    }

    public record ReachAiConfigManifest(
            String registryUrl,
            String appKey,
            String appSecretEnv,
            String projectCode,
            String projectName,
            String projectBaseUrl,
            String projectContextPath,
            String environment
    ) {
    }

    public record PlatformEndpoints(
            String skillPackageUrl,
            String manifestUrl,
            String sdkAccessCheckUrl,
            String reconcileToolsUrl
    ) {
    }

    public record EmbedManifest(
            String tokenPath,
            String defaultAgentId,
            String defaultAgentKeySlug,
            List<EmbedAgentManifest> allowedAgents
    ) {
    }

    public record EmbedAgentManifest(
            String id,
            String keySlug,
            String name,
            String projectCode,
            boolean enabled
    ) {
    }

    public record AgentProvisioningManifest(
            String model,
            String defaultKeySlug,
            String provisionAgentUrl,
            boolean idempotent,
            boolean createsSupervisorConfig,
            boolean activatesSupervisorConfig,
            String modelSelection,
            List<String> requiredSteps
    ) {
    }

    public record AgentSupervisorManifest(
            String model,
            String globalAgentKeySlug,
            String runtimeType,
            String workflowToolCatalog,
            AgentSupervisorEndpoints endpoints,
            WorkflowAiCodingManifest workflowAiCoding,
            List<String> requiredSteps
    ) {
    }

    public record AgentSupervisorEndpoints(
            String agentsUrl,
            String configVersionsUrlTemplate,
            String configDraftUrlTemplate,
            String workflowToolAttachUrlTemplate,
            String executeUrl
    ) {
    }

    public record WorkflowAiCodingManifest(
            String skillPackageUrl,
            String createUrl,
            String contextUrlTemplate,
            String patchUrlTemplate,
            String validateUrlTemplate,
            String runUrlTemplate,
            String versionsUrlTemplate,
            String publishUrlTemplate,
            String runsUrlTemplate,
            List<String> requiredSteps
    ) {
    }

    public record SecurityGuidance(String appSecretEnv, String message) {
    }
}
