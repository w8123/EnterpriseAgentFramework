package com.enterprise.ai.control.aiassist;

import com.enterprise.ai.control.client.capability.CapabilityProjectOnboardingClient;
import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import jakarta.servlet.http.HttpServletRequest;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/ai-coding/projects/{projectId}")
public class ControlAiCodingProjectController {

    private static final String AI_CODING_HEADER = "X-ReachAI-AiCoding-Key";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final CapabilityProjectOnboardingClient capabilityClient;
    private final RuntimeProxyClient runtimeClient;
    private final ControlProjectAgentProvisioningService provisioningService;

    public ControlAiCodingProjectController(CapabilityProjectOnboardingClient capabilityClient,
                                            RuntimeProxyClient runtimeClient,
                                            ControlProjectAgentProvisioningService provisioningService) {
        this.capabilityClient = capabilityClient;
        this.runtimeClient = runtimeClient;
        this.provisioningService = provisioningService;
    }

    @GetMapping("/manifest")
    public ResponseEntity<AiCodingGatewayManifest> manifest(@PathVariable Long projectId,
                                                            HttpServletRequest request) {
        try {
            Map<String, Object> project = capabilityClient.getOnboardingProjectById(projectId);
            String baseUrl = ControlAiAssistSkillController.requestBaseUrl(request);
            String root = baseUrl + "/api/ai-coding/projects/" + projectId;
            return ResponseEntity.ok(new AiCodingGatewayManifest(
                    "reachai.ai-coding.gateway.v3",
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
                    ControlAiAssistProjectController.sdkArtifacts(baseUrl),
                    ControlAiAssistProjectController.responseShapes(),
                    ControlAiAssistProjectController.gatewayChecklist(),
                    gatewayEndpoints(baseUrl, root, projectId),
                    contextCandidateSubmission(root),
                    capabilities(root, baseUrl, projectId)));
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
                    ControlAiAssistProjectController.sdkArtifacts(baseUrl),
                    ControlAiAssistProjectController.responseShapes(),
                    ControlAiAssistProjectController.gatewayChecklist(),
                    new ControlAiAssistProjectController.PlatformEndpoints(
                            baseUrl + "/api/ai-assist/skills/reachai-onboarding/latest.zip",
                            root + "/onboarding-manifest",
                            baseUrl + "/api/scan-projects/" + projectId + "/sdk-access-check",
                            baseUrl + "/api/scan-projects/" + projectId + "/tools/reconcile"),
                    new ControlAiAssistProjectController.EmbedManifest("/api/reachai/embed-token", null, null, List.of()),
                    provisioning,
                    agentSupervisorManifest(provisioning, baseUrl),
                    new ControlAiAssistProjectController.SecurityGuidance(
                            "REACHAI_REGISTRY_APP_SECRET",
                            "scripts/set-reachai-registry-secret.ps1",
                            "powershell -NoProfile -ExecutionPolicy Bypass -File "
                                    + "\"{skillExtractDir}/reachai-onboarding/scripts/"
                                    + "set-reachai-registry-secret.ps1\" -Target User",
                            "Do not paste or write the registry app secret into AI chat context.")));
        } catch (FeignException.NotFound ex) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/agent-supervisor/workflow-tools/attach")
    public ResponseEntity<Object> attachAgentSupervisorWorkflowTool(
            @PathVariable Long projectId,
            @RequestBody(required = false) Map<String, Object> body) {
        try {
            capabilityClient.getOnboardingProjectById(projectId);
        } catch (FeignException.NotFound ex) {
            return ResponseEntity.status(404).body(aiCodingError(
                    "CAPABILITY_PROJECT_NOT_FOUND",
                    "Capability project not found: " + projectId,
                    Map.of("projectId", projectId)));
        } catch (FeignException ex) {
            return ResponseEntity.status(statusOrBadGateway(ex.status())).body(aiCodingError(
                    "RUNTIME_DEPENDENCY_UNAVAILABLE",
                    "Capability project lookup failed: " + ex.getMessage(),
                    Map.of("projectId", projectId, "status", ex.status())));
        }
        try {
            Map<String, Object> request = body == null ? Map.of() : body;
            ResponseEntity<Object> response = runtimeClient.attachAgentSupervisorWorkflowTool(projectId, request);
            return ResponseEntity.status(response.getStatusCode()).body(response.getBody());
        } catch (FeignException ex) {
            return forwardRuntimeAiCodingError(ex);
        }
    }

    @PostMapping("/agents/provision")
    public ResponseEntity<Map<String, Object>> provisionProjectAgent(
            @PathVariable Long projectId,
            @RequestBody(required = false) Map<String, ?> request) {
        try {
            return ResponseEntity.ok(
                    provisioningService.provision(projectId, request));
        } catch (FeignException.NotFound ex) {
            return ResponseEntity.notFound().build();
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(409).body(Map.of(
                    "schema", "agent-provisioning.v2",
                    "message", ex.getMessage(),
                    "code", "SUPERVISOR_PROVISIONING_NOT_READY"));
        }
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
                "agent-provisioning.v2",
                ControlProjectAgentProvisioningService.pageCopilotKeySlug(
                        project.projectCode(),
                        project.id()),
                root + "/agents/provision",
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

    private ControlAiAssistProjectController.AgentSupervisorManifest agentSupervisorManifest(
            ControlAiAssistProjectController.AgentProvisioningManifest provisioning,
            String baseUrl) {
        String globalAgentKeySlug = provisioning.defaultKeySlug();
        return new ControlAiAssistProjectController.AgentSupervisorManifest(
                "agent-supervisor.workflow-tools.v1",
                globalAgentKeySlug,
                "AGENTSCOPE",
                "WORKFLOW_AS_TOOL_ALLOW_LIST",
                new ControlAiAssistProjectController.AgentSupervisorEndpoints(
                        baseUrl + "/api/agents",
                        baseUrl + "/api/agents/{agentId}/config-versions",
                        baseUrl + "/api/agents/{agentId}/config-versions/draft",
                        baseUrl + "/api/ai-coding/projects/{projectId}/agent-supervisor/workflow-tools/attach",
                        baseUrl + "/api/runtime/agents/execute"),
                new ControlAiAssistProjectController.WorkflowAiCodingManifest(
                        baseUrl + "/api/ai-assist/skills/workflow-ai-coding/latest.zip",
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
                                "Generic Workflow create defaults to workflowKind=GENERAL; Page Assistant must send workflowKind=PAGE_ASSISTANT explicitly.",
                                "Resource bindings may be replaced only while status=DRAFT; use the latest workflow.updatedAt as baseRevision.",
                                "Use availableModels/availableTools from /context; never invent modelInstanceId or tool ids.",
                                "Attach GENERAL/PAGE_ASSISTANT via agent-supervisor/workflow-tools/attach; page-assistant/attach-tool rejects GENERAL.",
                                "Read /context before patch; use workflow.updatedAt as baseRevision when saving.")),
                List.of(
                        "Provision or reuse one project-level page copilot Agent entry.",
                        "Store every executable graph as a runtime_workflow and publish an ACTIVE version before attachment.",
                        "Attach published Workflows via POST /api/ai-coding/projects/{projectId}/agent-supervisor/workflow-tools/attach using agentKeySlug (not internal agentId by default).",
                        "The Page Assistant-specific endpoint /api/workflows/{id}/page-assistant/attach-tool accepts PAGE_ASSISTANT only.",
                        "Publish a new Agent config version after changing its tool catalog.",
                        "Use only the published Agent config Workflow-as-Tool catalog for runtime selection."));
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
    private Map<String, Object> mapValue(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
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
        String taskProtocol = baseUrl + "/api/ai-coding/tasks/{taskId}";
        return new AiCodingGatewayEndpoints(
                root + "/manifest",
                contextCandidates,
                contextCandidates + "/batch",
                contextCandidates + "?traceId={submissionId}&status=PENDING",
                root + "/onboarding-manifest",
                baseUrl + "/api/ai-coding/handoffs/{handoffId}/activate",
                taskProtocol + "/context",
                taskProtocol + "/heartbeat",
                taskProtocol + "/events",
                taskProtocol + "/questions",
                taskProtocol + "/artifacts",
                baseUrl + "/api/workflows/ai-coding/workflows",
                baseUrl + "/api/workflows/{workflowId}/ai-coding/context",
                baseUrl + "/api/workflows/{workflowId}/ai-coding/resource-bindings",
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
                        "AI_CODING_TASK_PROTOCOL",
                        "AI Coding 任务协议",
                        "TASK",
                        baseUrl + "/api/ai-coding/tasks/{taskId}/context",
                        List.of(
                                "Activate the one-time handoff code first and use the returned task-scoped Bearer token.",
                                "Read scoped context first and write progress, questions, artifacts, and acceptance evidence back to the same task.",
                                "The project AI Coding key is not valid for task protocol endpoints.")),
                new AiCodingCapability(
                        "WORKFLOW_AI_CODING",
                        "Workflow AI Coding",
                        "WORKFLOW",
                        baseUrl + "/api/workflows/ai-coding/workflows",
                        List.of(
                                "Use workflow scoped endpoints for GraphSpec draft edits and validation.",
                                "Set first-class page resource bindings before the first publish; they are immutable afterward.")),
                new AiCodingCapability(
                        "CONTEXT_CANDIDATES",
                        "上下文候选提交",
                        "PROJECT",
                        root + "/context-candidates",
                        List.of("Submit PROJECT_DEV candidates for review under project " + projectId + ".")));
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

    private ResponseEntity<Object> forwardRuntimeAiCodingError(FeignException ex) {
        int status = statusOrBadGateway(ex.status());
        Object parsed = parseJsonBody(ex.contentUTF8());
        if (parsed instanceof Map<?, ?> map) {
            Object schema = map.get("schema");
            if ("ai-coding-error.v1".equals(schema) || map.containsKey("code")) {
                return ResponseEntity.status(status).body(parsed);
            }
        }
        return ResponseEntity.status(status).body(aiCodingError(
                "RUNTIME_DEPENDENCY_UNAVAILABLE",
                "Runtime attach failed: " + firstText(ex.getMessage(), "upstream error"),
                Map.of("status", status)));
    }

    private static Object parseJsonBody(String content) {
        if (!StringUtils.hasText(content)) {
            return null;
        }
        try {
            return JSON.readValue(content, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Map<String, Object> aiCodingError(String code, String message, Map<String, Object> details) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schema", "ai-coding-error.v1");
        body.put("code", code);
        body.put("message", message);
        body.put("details", details == null ? Map.of() : details);
        body.put("requestId", UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        return body;
    }

    private static int statusOrBadGateway(int status) {
        return status > 0 ? status : 502;
    }

    public record AiCodingGatewayManifest(String schema,
                                          AiCodingProject project,
                                          AiCodingAuth auth,
                                          List<ControlAiAssistProjectController.SdkArtifact> sdkArtifacts,
                                          Map<String, ControlAiAssistProjectController.ResponseShape> responseShapes,
                                          List<ControlAiAssistProjectController.GatewayChecklistItem> gatewayChecklist,
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
                                           String handoffActivationUrlTemplate,
                                           String taskContextUrlTemplate,
                                           String taskHeartbeatUrlTemplate,
                                           String taskEventsUrlTemplate,
                                           String taskQuestionsUrlTemplate,
                                           String taskArtifactsUrlTemplate,
                                           String workflowCreateUrl,
                                           String workflowContextUrlTemplate,
                                           String workflowResourceBindingsUrlTemplate,
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
