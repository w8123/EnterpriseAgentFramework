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
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ai-assist/projects/{projectId}")
@RequiredArgsConstructor
public class ControlAiAssistProjectController {

    private static final String SECRET_ENV_NAME = "REACHAI_REGISTRY_APP_SECRET";
    private static final String PAGE_COPILOT_KIND = "PAGE_COPILOT";
    private static final String WORKFLOW_AI_CODING_SKILL_NAME = "workflow-ai-coding";
    private static final String ONBOARDING_SKILL_NAME = "reachai-onboarding";

    private final CapabilityProjectOnboardingClient capabilityClient;

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
                    session,
                    null));
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
                    request == null || request.files() == null ? List.of() : request.files(),
                    null));
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
                "reachai.onboarding.v1",
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
                new PlatformEndpoints(
                        baseUrl + "/api/ai-assist/skills/" + ONBOARDING_SKILL_NAME + "/latest.zip",
                        projectApiRoot + "/onboarding-manifest",
                        baseUrl + "/api/scan-projects/" + projectManifest.id() + "/sdk-access-check",
                        baseUrl + "/api/scan-projects/" + projectManifest.id() + "/tools/reconcile"),
                embed,
                provisioning,
                buildAgentWorkflowManifest(projectManifest, provisioning, baseUrl),
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
                "agent-provisioning.v1",
                PAGE_COPILOT_KIND,
                defaultKeySlug,
                projectApiRoot + "/agents/provision",
                true,
                true,
                true,
                List.of(
                        "Call provisionAgentUrl before wiring embedded chat.",
                        "Use response.agent.keySlug as the business frontend agentId.",
                        "Do not ask the user to manually create or choose a project Agent during SDK onboarding."));
    }

    private AgentWorkflowManifest buildAgentWorkflowManifest(ProjectManifest project,
                                                             AgentProvisioningManifest provisioning,
                                                             String baseUrl) {
        String globalAgentKeySlug = provisioning.defaultKeySlug();
        return new AgentWorkflowManifest(
                "agent-workflow.decoupled.v1",
                globalAgentKeySlug,
                PAGE_COPILOT_KIND,
                "ai_workflow",
                "SDK_GRAPH",
                "Bind page/action/intent workflows to the project page copilot Agent instead of creating one agent per workflow.",
                new AgentWorkflowEndpoints(
                        baseUrl + "/api/agents",
                        baseUrl + "/api/workflows",
                        baseUrl + "/api/agents/" + globalAgentKeySlug + "/workflow-bindings",
                        baseUrl + "/api/agents/" + globalAgentKeySlug + "/workflow-bindings/resolve-preview"),
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
                        "Provision or reuse one project-level PAGE_COPILOT Agent entry.",
                        "Store every executable graph as an ai_workflow draft or version.",
                        "Create ai_agent_workflow_binding rows for DEFAULT, PAGE, ACTION, ROUTE, or INTENT routing.",
                        "Use Workflow AI Coding REST APIs or the workflow-ai-coding skill for draft edits, validation, debug runs, release readiness checks, and first publish."));
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
                        null));
        String overall = checks.stream().anyMatch(check -> "FAIL".equals(check.status()))
                ? "FAIL"
                : checks.stream().anyMatch(check -> "WARN".equals(check.status())) ? "WARN" : "PASS";
        return new SdkAccessCheckResponse(
                longValue(project.get("id")),
                stringValue(project.get("projectCode")),
                overall,
                List.of(
                        new SdkAccessReadiness("CODE_READY", "代码接入", overall, "SDK onboarding route is available"),
                        new SdkAccessReadiness("RUNTIME_READY", "Runtime 就绪", overall, "Runtime readiness requires business service heartbeat"),
                        new SdkAccessReadiness("E2E_READY", "端到端", overall, "Run a business API call to complete final verification")),
                checks);
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
            PlatformEndpoints endpoints,
            EmbedManifest embed,
            AgentProvisioningManifest agentProvisioning,
            AgentWorkflowManifest agentWorkflow,
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

    public record PageAssistantWorkflowBinding(String agentId,
                                               String agentKeySlug,
                                               String workflowId,
                                               String workflowKeySlug,
                                               Long bindingId) {
    }

    public record PageAssistantCatalogSyncResponse(String projectCode,
                                                   String appId,
                                                   String pageKey,
                                                   int actionCount,
                                                   AiAccessSessionView session,
                                                   PageAssistantWorkflowBinding workflowBinding) {
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
                                                    List<PageAssistantFileEvidence> fileEvidence,
                                                    PageAssistantWorkflowBinding workflowBinding) {
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
            String defaultAgentKind,
            String defaultKeySlug,
            String provisionAgentUrl,
            boolean idempotent,
            boolean createsDefaultWorkflow,
            boolean createsDefaultBinding,
            List<String> requiredSteps
    ) {
    }

    public record AgentWorkflowManifest(
            String model,
            String globalAgentKeySlug,
            String globalAgentKind,
            String workflowStorage,
            String sdkGraphWorkflowType,
            String bindingStrategy,
            AgentWorkflowEndpoints endpoints,
            WorkflowAiCodingManifest workflowAiCoding,
            List<String> requiredSteps
    ) {
    }

    public record AgentWorkflowEndpoints(
            String agentsUrl,
            String workflowsUrl,
            String globalAgentBindingsUrl,
            String resolvePreviewUrl
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
