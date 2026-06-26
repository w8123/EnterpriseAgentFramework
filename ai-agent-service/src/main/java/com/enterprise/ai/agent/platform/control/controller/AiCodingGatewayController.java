package com.enterprise.ai.agent.platform.control.controller;

import com.enterprise.ai.agent.aicoding.AiCodingAccessGuard;
import com.enterprise.ai.agent.platform.control.context.ContextSourceType;
import com.enterprise.ai.agent.platform.control.context.MemoryLane;
import com.enterprise.ai.agent.platform.control.context.memory.ContextMemoryCandidateType;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectEntity;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

@RestController
@RequestMapping("/api/ai-coding/projects/{projectId}")
@RequiredArgsConstructor
public class AiCodingGatewayController {

    private static final String AI_CODING_KEY_HEADER = "X-ReachAI-AiCoding-Key";
    private static final String DEFAULT_TENANT_ID = "default";
    private static final String DEFAULT_CONTEXT_SCAN_ORIGIN = "AI_CODING_CONTEXT_SCAN";
    private static final String SUBMISSION_ID_PREFIX = "ai-coding-submission-";
    private static final String SUBMISSION_METADATA_KEY = "aiCodingSubmission";

    private final AiCodingAccessGuard accessGuard;

    @GetMapping("/manifest")
    public ResponseEntity<AiCodingGatewayManifestResponse> manifest(
            @PathVariable Long projectId,
            HttpServletRequest request) {
        ScanProjectEntity project = accessGuard.requireProjectAccess(projectId);
        String baseUrl = requestBaseUrl(request);
        AiCodingGatewayEndpoints endpoints = buildEndpoints(baseUrl, projectId);
        return ResponseEntity.ok(new AiCodingGatewayManifestResponse(
                "reachai.ai-coding.gateway.v1",
                toProjectScope(project),
                new AiCodingGatewayAuth(
                        AI_CODING_KEY_HEADER,
                        accessGuard.auditActorLabel(projectId),
                        List.of(
                                "Send the project AI Coding access key as the " + AI_CODING_KEY_HEADER + " header.",
                                "Do not store or echo the raw access key in submitted candidates, logs, or handoff notes."
                        )),
                endpoints,
                buildContextCandidateSubmission(endpoints),
                buildCapabilities(endpoints)
        ));
    }

    private AiCodingGatewayProjectScope toProjectScope(ScanProjectEntity project) {
        return new AiCodingGatewayProjectScope(
                project.getId(),
                emptyToNull(project.getProjectCode()),
                emptyToNull(project.getName()),
                emptyToNull(project.getProjectKind()),
                emptyToNull(project.getEnvironment()));
    }

    private AiCodingGatewayEndpoints buildEndpoints(String baseUrl, Long projectId) {
        String projectRoot = baseUrl + "/api/ai-coding/projects/" + projectId;
        String workflowRoot = baseUrl + "/api/workflows";
        return new AiCodingGatewayEndpoints(
                projectRoot + "/manifest",
                projectRoot + "/context-candidates",
                projectRoot + "/context-candidates/batch",
                projectRoot + "/context-candidates?traceId={submissionId}&status=PENDING",
                projectRoot + "/onboarding-manifest",
                projectRoot + "/access-sessions",
                projectRoot + "/access-sessions/latest",
                projectRoot + "/page-assistant/onboarding-manifest",
                projectRoot + "/page-assistant/sessions",
                workflowRoot + "/ai-coding/workflows",
                workflowRoot + "/{workflowId}/ai-coding/context",
                workflowRoot + "/{workflowId}/ai-coding/patch",
                workflowRoot + "/{workflowId}/ai-coding/validate",
                workflowRoot + "/{workflowId}/ai-coding/run",
                workflowRoot + "/{workflowId}/ai-coding/versions",
                workflowRoot + "/{workflowId}/ai-coding/publish",
                workflowRoot + "/{workflowId}/ai-coding/runs",
                baseUrl + "/context/governance?tab=candidates&projectId=" + projectId,
                baseUrl + "/context/governance?tab=audit&projectId=" + projectId + "&traceId={submissionId}"
        );
    }

    private AiCodingGatewayContextCandidateSubmission buildContextCandidateSubmission(
            AiCodingGatewayEndpoints endpoints) {
        return new AiCodingGatewayContextCandidateSubmission(
                "reachai.context-candidate-submission.v1",
                endpoints.contextCandidatesUrl(),
                endpoints.contextCandidatesBatchUrl(),
                "PENDING_HUMAN_REVIEW",
                MemoryLane.PROJECT_DEV.name(),
                DEFAULT_TENANT_ID,
                ContextSourceType.CODE.name(),
                ContextMemoryCandidateType.NOTE.name(),
                List.of("content"),
                Arrays.stream(ContextMemoryCandidateType.values()).map(Enum::name).toList(),
                Arrays.stream(ContextSourceType.values()).map(Enum::name).toList(),
                buildTraceMetadata(),
                List.of("tenantId", "memoryLane", "projectId", "projectCode", "proposedBy",
                        "traceId", "sessionId", "origin", "visibility", "confidence", "trustLevel", "expiresAt",
                        "userId", "globalUserId", "externalUserId"),
                List.of(
                        "Submit code-scan findings as candidates; platform users approve or reject them later.",
                        "Batch submissions reject any item missing content before creating candidates.",
                        "Status checks require ReachAI generated ai-coding-submission-* traceId/submissionId and return PENDING candidates only.",
                        "Use PAGE_CONTEXT, API_CONTEXT, or WORKFLOW_CONTEXT when the finding targets that object type.",
                        "WORKFLOW_CONTEXT requires workflowId or workflowKey before approval.",
                        "PAGE_CONTEXT requires pageInstanceId before approval.",
                        "API_CONTEXT requires sourceRef before approval.",
                        "Server-side gateway scope overwrites tenant, project, memory lane, and proposedBy.",
                        "Server-side gateway owns confidence, trustLevel, and expiresAt; client suggestions are metadata only.",
                        "Do not attach runtime user identity; PROJECT_DEV candidates are project-scoped.",
                        "PROJECT_DEV candidate visibility is always normalized to PROJECT before review or approval."
                ));
    }

    private AiCodingGatewayTraceMetadata buildTraceMetadata() {
        return new AiCodingGatewayTraceMetadata(
                SUBMISSION_METADATA_KEY,
                SUBMISSION_ID_PREFIX,
                DEFAULT_CONTEXT_SCAN_ORIGIN,
                "traceId is generated by ReachAI as the AI Coding submissionId; client traceId is stored in metadata.aiCodingSubmission.clientTraceId",
                "sessionId is generated by ReachAI as the AI Coding submissionId; client sessionId is stored in metadata.aiCodingSubmission.clientSessionId");
    }

    private List<AiCodingGatewayCapability> buildCapabilities(AiCodingGatewayEndpoints endpoints) {
        return List.of(
                new AiCodingGatewayCapability(
                        "SDK_ACCESS",
                        "SDK quick access",
                        "PROJECT",
                        endpoints.sdkAccessManifestUrl(),
                        List.of("Read the SDK onboarding manifest.", "Use access sessions for step progress and checks.")),
                new AiCodingGatewayCapability(
                        "PAGE_ASSISTANT",
                        "Page Assistant onboarding",
                        "PAGE",
                        endpoints.pageAssistantManifestUrl(),
                        List.of("Register page context and page actions.", "Keep page semantics separate from Workflow semantics.")),
                new AiCodingGatewayCapability(
                        "WORKFLOW_AI_CODING",
                        "Workflow GraphSpec editing",
                        "WORKFLOW",
                        endpoints.workflowContextUrlTemplate(),
                        List.of("Read workflow context before patching.", "Mutate GraphSpec, not canvas layout.")),
                new AiCodingGatewayCapability(
                        "CONTEXT_CANDIDATES",
                        "Code scan context candidates",
                        "PROJECT",
                        endpoints.contextCandidatesUrl(),
                        List.of("Submit PROJECT_DEV memory candidates for human review.", "Do not directly mutate runtime user memory."))
        );
    }

    private static String requestBaseUrl(HttpServletRequest request) {
        String scheme = headerOrDefault(request, "X-Forwarded-Proto", request.getScheme());
        String host = headerOrDefault(request, "X-Forwarded-Host", request.getServerName());
        String port = request.getServerPort() <= 0 ? "" : ":" + request.getServerPort();
        if (host.contains(":") || ("http".equalsIgnoreCase(scheme) && request.getServerPort() == 80)
                || ("https".equalsIgnoreCase(scheme) && request.getServerPort() == 443)) {
            port = "";
        }
        String contextPath = request.getContextPath() == null ? "" : request.getContextPath();
        return scheme + "://" + host + port + contextPath;
    }

    private static String headerOrDefault(HttpServletRequest request, String name, String fallback) {
        String value = request.getHeader(name);
        return StringUtils.hasText(value) ? value.split(",")[0].trim() : fallback;
    }

    private static String emptyToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    public record AiCodingGatewayManifestResponse(
            String schema,
            AiCodingGatewayProjectScope project,
            AiCodingGatewayAuth auth,
            AiCodingGatewayEndpoints endpoints,
            AiCodingGatewayContextCandidateSubmission contextCandidateSubmission,
            List<AiCodingGatewayCapability> capabilities
    ) {
    }

    public record AiCodingGatewayProjectScope(
            Long id,
            String projectCode,
            String name,
            String projectKind,
            String environment
    ) {
    }

    public record AiCodingGatewayAuth(
            String headerName,
            String auditActor,
            List<String> guidance
    ) {
    }

    public record AiCodingGatewayEndpoints(
            String manifestUrl,
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
            String contextCandidateAuditUrlTemplate
    ) {
    }

    public record AiCodingGatewayContextCandidateSubmission(
            String schema,
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
            AiCodingGatewayTraceMetadata traceMetadata,
            List<String> serverControlledFields,
            List<String> guidance
    ) {
    }

    public record AiCodingGatewayTraceMetadata(
            String metadataKey,
            String generatedSubmissionIdPrefix,
            String defaultOrigin,
            String traceIdPolicy,
            String sessionIdPolicy
    ) {
    }

    public record AiCodingGatewayCapability(
            String key,
            String title,
            String targetType,
            String entryUrl,
            List<String> guidance
    ) {
    }
}
