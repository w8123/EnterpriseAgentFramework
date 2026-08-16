package com.enterprise.ai.control.aiassist;

import com.enterprise.ai.control.client.capability.CapabilityProjectOnboardingClient;
import feign.FeignException;
import jakarta.servlet.http.HttpServletRequest;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ai-assist/projects/{projectId}")
public class ControlAiAssistProjectController {

    private static final String SECRET_ENV_NAME = "REACHAI_REGISTRY_APP_SECRET";
    private static final String WORKFLOW_AI_CODING_SKILL_NAME = "workflow-ai-coding";
    private static final String ONBOARDING_SKILL_NAME = "reachai-onboarding";

    private final CapabilityProjectOnboardingClient capabilityClient;
    public ControlAiAssistProjectController(
            CapabilityProjectOnboardingClient capabilityClient) {
        this.capabilityClient = capabilityClient;
    }

    static List<SdkArtifact> sdkArtifacts() {
        return sdkArtifacts(null);
    }

    static List<SdkArtifact> sdkArtifacts(String baseUrl) {
        var artifacts = new java.util.ArrayList<SdkArtifact>(
                ControlJavaSdkArtifactSupport.mavenArtifacts(baseUrl));
        artifacts.add(ControlEmbedChatArtifactSupport.npmArtifact(baseUrl));
        return List.copyOf(artifacts);
    }

    static List<GatewayChecklistItem> gatewayChecklist() {
        return List.of(
                new GatewayChecklistItem(
                        "embed-route-forwarding",
                        "Token broker stays behind business login; /api/reachai/embed/** rewrites to ReachAI /api/embed/** and forwards Authorization: Bearer <embedToken>.",
                        true,
                        "Probe token broker + embed proxy rewrite",
                        "Chat cannot authenticate or call embed APIs"),
                new GatewayChecklistItem(
                        "registry-callback-route",
                        "Route /reachai/registry/** to the Starter service and preserve X-ReachAI-* signature headers.",
                        true,
                        "ReachAI can POST /reachai/registry/capabilities/sync",
                        "API Management SDK sync fails"),
                new GatewayChecklistItem(
                        "cors-options",
                        "When gateway and ReachAI both write CORS headers, dedupe with DedupeResponseHeader RETAIN_FIRST.",
                        true,
                        "Browser Network shows real status, not 0 Unknown Error",
                        "Failures hidden as CORS/status 0"),
                new GatewayChecklistItem(
                        "security-webfilter-chain",
                        "Independent high-priority SecurityWebFilterChain for /api/reachai/embed/** without business oauth2ResourceServer.",
                        true,
                        "Dedicated WebFlux chain without oauth2ResourceServer for embed",
                        "Embed Bearer rejected as business JWT"),
                new GatewayChecklistItem(
                        "ignore-urls-remove-jwt",
                        "IgnoreUrlsRemoveJwtFilter / RemoveJwtFilter / RemoveRequestHeader=Authorization must not clear Authorization on /api/reachai/embed/**.",
                        true,
                        "Authorization header reaches ReachAI unchanged",
                        "Embed token stripped before proxy"),
                new GatewayChecklistItem(
                        "embed-aicoding-auth-boundary",
                        "Keep /api/reachai/embed-token on business login; keep /api/reachai/embed/** anonymous to business JWT; AI Coding key only in headers.",
                        true,
                        "Token broker uses business login; chat uses embed token; AI Coding uses header key",
                        "Secret leakage or auth mix-up"));
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
        shapes.put("sdkAccessCheck", new ResponseShape(
                "bare-json",
                Map.of(
                        "overallStatus", "overallStatus",
                        "checks", "checks",
                        "readiness", "readiness"),
                "SDK access check is a platform-console response for Starter registration, heartbeat and signed callback facts; "
                        + "it is not ApiResult-wrapped and does not prove browser Embed E2E."));
        shapes.put("onboardingManifest", new ResponseShape(
                "bare-json",
                Map.of(
                        "project", "project",
                        "sdkArtifacts", "sdkArtifacts",
                        "gatewayChecklist", "gatewayChecklist",
                        "responseShapes", "responseShapes",
                        "agentProvisioning", "agentProvisioning"),
                "Manifest responses are top-level JSON contracts for AI coding tools. "
                        + "gatewayChecklist is a top-level object list, not a ResponseShape."));
        shapes.put("workflowToolAttach", new ResponseShape(
                "bare-json",
                Map.of(
                        "request.workflowId", "workflowId",
                        "request.agentKeySlug", "agentKeySlug",
                        "request.agentId", "agentId (internal id only; mutually exclusive with agentKeySlug)",
                        "request.replaceWorkflowId", "optional exact currently attached Workflow id to replace; omit for additive attachment",
                        "request.modelInstanceId", "modelInstanceId",
                        "request.inputSchema", "Workflow tool JSON Schema override",
                        "request.riskLevel", "READ / WRITE / PAGE_ACTION / IRREVERSIBLE",
                        "request.readOnly", "explicit execution concurrency classification",
                        "success.schema", "workflow-tool-attachment.v1",
                        "error.schema", "ai-coding-error.v1"),
                "Generic attach: POST /api/ai-coding/projects/{projectId}/agent-supervisor/workflow-tools/attach. "
                        + "Default workflowKind on create is GENERAL; Page Assistant must send PAGE_ASSISTANT. "
                        + "The Page Assistant-specific endpoint /api/workflows/{id}/page-assistant/attach-tool rejects GENERAL with WORKFLOW_KIND_NOT_SUPPORTED. "
                        + "Replacement is explicit and exact: replaceWorkflowId must match project and workflowKind, PAGE_ASSISTANT must also match TARGET PAGE; only that attached predecessor is removed and all other tools are preserved. "
                        + "Stable error codes: WORKFLOW_NOT_FOUND, WORKFLOW_PROJECT_MISSING, WORKFLOW_PROJECT_MISMATCH, "
                        + "WORKFLOW_NOT_ACTIVE, WORKFLOW_KIND_NOT_SUPPORTED, AGENT_NOT_FOUND, AGENT_PROJECT_MISMATCH, "
                        + "MODEL_INSTANCE_NOT_ACTIVE, NO_ACTIVE_LLM, WORKFLOW_REPLACEMENT_INVALID, "
                        + "RUNTIME_DEPENDENCY_UNAVAILABLE, ATTACHMENT_PUBLISH_FAILED."));
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
                sdkArtifacts(baseUrl),
                responseShapes(),
                gatewayChecklist(),
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
                        "scripts/set-reachai-registry-secret.ps1",
                        "powershell -NoProfile -ExecutionPolicy Bypass -File "
                                + "\"{skillExtractDir}/reachai-onboarding/scripts/"
                                + "set-reachai-registry-secret.ps1\" -Target User",
                        "Do not paste or write the registry app secret into AI chat context. Store it in a local environment variable or secret manager."));
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
                        baseUrl + "/api/ai-coding/projects/" + project.id() + "/agent-supervisor/workflow-tools/attach",
                        baseUrl + "/api/runtime/agents/execute"),
                new WorkflowAiCodingManifest(
                        baseUrl + "/api/ai-assist/skills/" + WORKFLOW_AI_CODING_SKILL_NAME + "/latest.zip",
                        baseUrl + "/api/workflows/ai-coding/workflows",
                        baseUrl + "/api/workflows/{workflowId}/ai-coding/context",
                        baseUrl + "/api/workflows/{workflowId}/ai-coding/resource-bindings",
                        baseUrl + "/api/workflows/{workflowId}/ai-coding/patch",
                        baseUrl + "/api/workflows/{workflowId}/ai-coding/validate",
                        baseUrl + "/api/workflows/{workflowId}/ai-coding/run",
                        baseUrl + "/api/workflows/{workflowId}/ai-coding/versions",
                        baseUrl + "/api/workflows/{workflowId}/ai-coding/publish",
                        baseUrl + "/api/workflows/{workflowId}/ai-coding/runs",
                        List.of(
                                "Download and install the workflow-ai-coding skill before editing graphs from AI tools.",
                                "All Workflow AI Coding endpoints require project aiCodingKey via header X-ReachAI-AiCoding-Key; manage the key in project detail.",
                                "Generic Workflow create defaults to workflowKind=GENERAL; Page Assistant must send workflowKind=PAGE_ASSISTANT explicitly.",
                                "Resource bindings may be replaced only while status=DRAFT; use the latest workflow.updatedAt as baseRevision.",
                                "Use availableModels/availableTools from /context; never invent modelInstanceId or tool ids.",
                                "Attach via agent-supervisor/workflow-tools/attach (GENERAL and PAGE_ASSISTANT). page-assistant/attach-tool rejects GENERAL.",
                                "Read /context before patch; use workflow.updatedAt as baseRevision when saving.",
                                "After the first valid workflow draft is saved, call /publish once to create the initial ACTIVE workflow version.")),
                List.of(
                        "Provision or reuse one project-level page copilot Agent entry.",
                        "Store every executable graph as a runtime_workflow and publish an ACTIVE version before attachment.",
                        "Attach published Workflows via the generic agent-supervisor workflow-tools attach endpoint using agentKeySlug.",
                        "The Page Assistant-specific endpoint accepts PAGE_ASSISTANT only.",
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
                "WARN",
                "CONFIGURED_NOT_PROBED: "
                        + (loopback
                        ? "已推导同步回调目标（回环地址），未主动探测可达性"
                        : "已推导同步回调目标，未主动探测可达性/鉴权"),
                targetUrl);
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
            List<GatewayChecklistItem> gatewayChecklist,
            PlatformEndpoints endpoints,
            EmbedManifest embed,
            AgentProvisioningManifest agentProvisioning,
            AgentSupervisorManifest agentSupervisor,
            SecurityGuidance security
    ) {
    }

    public record GatewayChecklistItem(
            String id,
            String description,
            boolean required,
            String verificationHint,
            String failureImpact
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
                              String notes,
                              String format,
                              String downloadUrl,
                              String integritySha256,
                              String installCommand,
                              String fallbackPolicy,
                              List<String> requiredFiles,
                              String artifactPathWithinSkill,
                              String installWorkingDirectory,
                              String installCommandTemplate,
                              String pomDownloadUrl,
                              String pomIntegritySha256) {
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
            String resourceBindingsUrlTemplate,
            String patchUrlTemplate,
            String validateUrlTemplate,
            String runUrlTemplate,
            String versionsUrlTemplate,
            String publishUrlTemplate,
            String runsUrlTemplate,
            List<String> requiredSteps
    ) {
    }

    public record SecurityGuidance(
            String appSecretEnv,
            String secretSetupScriptWithinSkill,
            String secretSetupCommandTemplate,
            String message) {
    }
}
