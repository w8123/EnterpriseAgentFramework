package com.enterprise.ai.agent.aicoding;

import com.enterprise.ai.agent.platform.control.context.ContextSourceType;
import com.enterprise.ai.agent.platform.control.context.ContextVisibility;
import com.enterprise.ai.agent.platform.control.context.MemoryLane;
import com.enterprise.ai.agent.platform.control.context.memory.ContextMemoryCandidateCreateRequest;
import com.enterprise.ai.agent.platform.control.context.memory.ContextMemoryCandidateQueryRequest;
import com.enterprise.ai.agent.platform.control.context.memory.ContextMemoryCandidateResponse;
import com.enterprise.ai.agent.platform.control.context.memory.ContextMemoryCandidateService;
import com.enterprise.ai.agent.platform.control.context.memory.ContextMemoryCandidateType;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectEntity;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AiCodingContextCandidateSubmissionService {

    private static final String DEFAULT_TENANT_ID = "default";
    private static final String DEFAULT_ORIGIN = "AI_CODING_CONTEXT_SCAN";
    private static final String SUBMISSION_ID_PREFIX = "ai-coding-submission-";
    private static final String SUBMISSION_METADATA_KEY = "aiCodingSubmission";

    private final AiCodingAccessGuard accessGuard;
    private final ContextMemoryCandidateService candidateService;
    private final ObjectMapper objectMapper;

    public ContextMemoryCandidateResponse createCandidate(Long projectId,
                                                          ContextMemoryCandidateCreateRequest request) {
        ScanProjectEntity project = accessGuard.requireProjectAccess(projectId);
        String submissionId = generateSubmissionId();
        return candidateService.createCandidate(toProjectDevCreateRequest(projectId, project, request, submissionId));
    }

    public List<ContextMemoryCandidateResponse> createCandidateBatch(
            Long projectId,
            List<ContextMemoryCandidateCreateRequest> requests) {
        ScanProjectEntity project = accessGuard.requireProjectAccess(projectId);
        List<ContextMemoryCandidateCreateRequest> sourceRequests =
                requests == null ? List.of() : requests;
        validateSourceRequests(sourceRequests);
        String submissionId = generateSubmissionId();
        List<ContextMemoryCandidateCreateRequest> effectiveRequests =
                sourceRequests.stream()
                        .map(request -> toProjectDevCreateRequest(projectId, project, request, submissionId))
                        .toList();
        return candidateService.createCandidates(effectiveRequests);
    }

    public List<ContextMemoryCandidateResponse> listCandidates(Long projectId,
                                                               ContextMemoryCandidateQueryRequest query) {
        ScanProjectEntity project = accessGuard.requireProjectAccess(projectId);
        validateStatusCheckQuery(query);
        ContextMemoryCandidateQueryRequest effective = new ContextMemoryCandidateQueryRequest();
        if (query != null) {
            BeanUtils.copyProperties(query, effective);
        }
        effective.setMemoryLane(MemoryLane.PROJECT_DEV.name());
        effective.setTenantId(DEFAULT_TENANT_ID);
        effective.setProjectId(projectId);
        effective.setProjectCode(project.getProjectCode());
        effective.setTraceId(query.getTraceId().trim());
        effective.setStatus("PENDING");
        return candidateService.listCandidates(effective);
    }

    private void validateSourceRequests(List<ContextMemoryCandidateCreateRequest> requests) {
        for (ContextMemoryCandidateCreateRequest request : requests) {
            if (request == null || !StringUtils.hasText(request.getContent())) {
                throw new IllegalArgumentException("content is required");
            }
        }
    }

    private void validateStatusCheckQuery(ContextMemoryCandidateQueryRequest query) {
        if (query == null || !StringUtils.hasText(query.getTraceId())) {
            throw new IllegalArgumentException("traceId is required for AI Coding candidate status check");
        }
        if (!query.getTraceId().trim().startsWith(SUBMISSION_ID_PREFIX)) {
            throw new IllegalArgumentException("traceId must be a ReachAI submissionId");
        }
        if (StringUtils.hasText(query.getStatus())
                && !"PENDING".equalsIgnoreCase(query.getStatus().trim())) {
            throw new IllegalArgumentException("Only PENDING status checks are allowed for AI Coding candidates");
        }
    }

    private ContextMemoryCandidateCreateRequest toProjectDevCreateRequest(
            Long projectId,
            ScanProjectEntity project,
            ContextMemoryCandidateCreateRequest request,
            String submissionId) {
        ContextMemoryCandidateCreateRequest effective = new ContextMemoryCandidateCreateRequest();
        if (request != null) {
            BeanUtils.copyProperties(request, effective);
        }
        String clientTraceId = request == null ? null : trimToNull(request.getTraceId());
        String clientSessionId = request == null ? null : trimToNull(request.getSessionId());
        String clientConfidence = request == null || request.getConfidence() == null
                ? null
                : request.getConfidence().toPlainString();
        String clientTrustLevel = request == null ? null : trimToNull(request.getTrustLevel());
        String clientExpiresAt = request == null || request.getExpiresAt() == null
                ? null
                : request.getExpiresAt().toString();
        effective.setMemoryLane(MemoryLane.PROJECT_DEV.name());
        effective.setTenantId(DEFAULT_TENANT_ID);
        effective.setUserId(null);
        effective.setGlobalUserId(null);
        effective.setExternalUserId(null);
        effective.setVisibility(ContextVisibility.PROJECT.name());
        if (!StringUtils.hasText(effective.getSourceType())) {
            effective.setSourceType(ContextSourceType.CODE.name());
        }
        if (!StringUtils.hasText(effective.getCandidateType())) {
            effective.setCandidateType(ContextMemoryCandidateType.NOTE.name());
        }
        effective.setProjectId(projectId);
        effective.setProjectCode(project.getProjectCode());
        effective.setTraceId(submissionId);
        effective.setSessionId(submissionId);
        effective.setOrigin(DEFAULT_ORIGIN);
        effective.setConfidence(null);
        effective.setTrustLevel(null);
        effective.setExpiresAt(null);
        effective.setProposedBy(accessGuard.auditActorLabel(projectId));
        effective.setMetadataJson(enrichSubmissionMetadata(effective.getMetadataJson(),
                submissionId, clientTraceId, clientSessionId, clientConfidence, clientTrustLevel, clientExpiresAt,
                projectId, project));
        return effective;
    }

    private String generateSubmissionId() {
        return SUBMISSION_ID_PREFIX + UUID.randomUUID().toString().replace("-", "");
    }

    private String enrichSubmissionMetadata(String metadataJson,
                                            String submissionId,
                                            String clientTraceId,
                                            String clientSessionId,
                                            String clientConfidence,
                                            String clientTrustLevel,
                                            String clientExpiresAt,
                                            Long projectId,
                                            ScanProjectEntity project) {
        Map<String, Object> metadata = parseMetadata(metadataJson);
        Map<String, Object> submission = new LinkedHashMap<>();
        submission.put("schema", "reachai.ai-coding.submission.v1");
        submission.put("submissionId", submissionId);
        submission.put("entrypoint", "CONTEXT_CANDIDATES");
        submission.put("projectId", projectId);
        submission.put("projectCode", project.getProjectCode());
        submission.put("memoryLane", MemoryLane.PROJECT_DEV.name());
        if (StringUtils.hasText(clientTraceId)) {
            submission.put("clientTraceId", clientTraceId);
        }
        if (StringUtils.hasText(clientSessionId)) {
            submission.put("clientSessionId", clientSessionId);
        }
        if (StringUtils.hasText(clientConfidence)) {
            submission.put("clientConfidence", clientConfidence);
        }
        if (StringUtils.hasText(clientTrustLevel)) {
            submission.put("clientTrustLevel", clientTrustLevel);
        }
        if (StringUtils.hasText(clientExpiresAt)) {
            submission.put("clientExpiresAt", clientExpiresAt);
        }
        metadata.put(SUBMISSION_METADATA_KEY, submission);
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException ex) {
            return "{\"aiCodingSubmission\":{\"submissionId\":\"" + submissionId + "\"}}";
        }
    }

    private Map<String, Object> parseMetadata(String metadataJson) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (!StringUtils.hasText(metadataJson)) {
            return metadata;
        }
        try {
            Object parsed = objectMapper.readValue(metadataJson, Object.class);
            if (parsed instanceof Map<?, ?>) {
                return objectMapper.convertValue(parsed, new TypeReference<>() {
                });
            }
            metadata.put("rawMetadataJson", metadataJson);
            return metadata;
        } catch (JsonProcessingException ex) {
            metadata.put("rawMetadataJson", metadataJson);
            return metadata;
        }
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
