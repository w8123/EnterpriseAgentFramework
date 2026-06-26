package com.enterprise.ai.agent.platform.control.context.memory;

import com.enterprise.ai.agent.platform.control.context.ContextItemResponse;
import com.enterprise.ai.agent.platform.control.context.ContextQueryRequest;
import com.enterprise.ai.agent.platform.control.context.ContextRetrievalService;
import com.enterprise.ai.agent.platform.control.context.ContextSearchResult;
import com.enterprise.ai.agent.platform.control.context.ContextSourceType;
import com.enterprise.ai.agent.platform.control.context.MemoryLane;
import com.enterprise.ai.agent.platform.control.identity.EmbedSessionEntity;
import com.enterprise.ai.agent.platform.control.identity.EmbedTokenClaims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class RuntimeMemoryCandidateService {

    static final List<String> MEMORY_INTENT_TRIGGERS = List.of(
            "记住",
            "请记住",
            "以后都",
            "我的偏好是",
            "我喜欢");

    private final ContextMemoryCandidateService candidateService;
    private final RuntimeMemoryExtractor memoryExtractor;
    private final ContextRetrievalService retrievalService;

    public RuntimeMemoryCandidateResult tryCreateFromInteraction(EmbedSessionEntity session,
                                                                   EmbedTokenClaims claims,
                                                                   Map<String, Object> metadata,
                                                                   String userMessage,
                                                                   String traceId) {
        return tryCreateFromInteraction(session, claims, metadata, userMessage, traceId,
                RuntimeMemoryCandidateIdentity.empty());
    }

    public RuntimeMemoryCandidateResult tryCreateFromInteraction(EmbedSessionEntity session,
                                                                   EmbedTokenClaims claims,
                                                                   Map<String, Object> metadata,
                                                                   String userMessage,
                                                                   String traceId,
                                                                   RuntimeMemoryCandidateIdentity identity) {
        return tryCreateFromInteraction(session, claims, metadata, userMessage, null, traceId, identity);
    }

    public RuntimeMemoryCandidateResult tryCreateFromInteraction(EmbedSessionEntity session,
                                                                   EmbedTokenClaims claims,
                                                                   Map<String, Object> metadata,
                                                                   String userMessage,
                                                                   String assistantReply,
                                                                   String traceId,
                                                                   RuntimeMemoryCandidateIdentity identity) {
        if (!StringUtils.hasText(userMessage)) {
            return skipped("empty-message");
        }
        String requestedModelInstanceId = firstText(
                stringValue(metadata == null ? null : metadata.get("memoryExtractionModelInstanceId")),
                stringValue(metadata == null ? null : metadata.get("modelInstanceId")));
        if (!StringUtils.hasText(requestedModelInstanceId) && !hasExplicitMemoryIntent(userMessage)) {
            return skipped("no-memory-intent");
        }
        try {
            String tenantId = firstText(
                    stringValue(metadata == null ? null : metadata.get("tenantId")),
                    session == null ? null : session.getTenantId());
            if (!StringUtils.hasText(tenantId)) {
                return skipped("missing-tenant-id");
            }
            String globalUserId = firstText(
                    stringValue(metadata == null ? null : metadata.get("globalUserId")),
                    claims == null ? null : claims.getGlobalUserId(),
                    session == null ? null : session.getGlobalUserId());
            String externalUserId = firstText(
                    stringValue(metadata == null ? null : metadata.get("externalUserId")),
                    claims == null ? null : claims.getExternalUserId(),
                    session == null ? null : session.getExternalUserId());
            String resolvedUserId = candidateService.resolveUserId(globalUserId, externalUserId, null);
            if (!StringUtils.hasText(resolvedUserId)) {
                return skipped("missing-user-id");
            }

            RuntimeMemoryCandidateIdentity safeIdentity = identity == null
                    ? RuntimeMemoryCandidateIdentity.empty()
                    : identity;
            String modelInstanceId = requestedModelInstanceId;

            if (StringUtils.hasText(modelInstanceId)) {
                RuntimeMemoryCandidateResult llmResult = tryCreateFromLlmExtraction(
                        tenantId, metadata, session, globalUserId, externalUserId, resolvedUserId,
                        safeIdentity, userMessage, assistantReply, traceId, modelInstanceId);
                if (llmResult.isCreated() || !hasExplicitMemoryIntent(userMessage)) {
                    return llmResult;
                }
            }

            if (!hasExplicitMemoryIntent(userMessage)) {
                return skipped("no-memory-intent");
            }
            return createRuleCandidate(tenantId, metadata, session, globalUserId, externalUserId,
                    resolvedUserId, safeIdentity, userMessage, traceId,
                    StringUtils.hasText(modelInstanceId) ? "RULE_FALLBACK" : "RULE");
        } catch (Exception ex) {
            log.warn("[RuntimeMemoryCandidate] create failed, skip: sessionId={}, reason={}",
                    session == null ? null : session.getSessionId(), ex.getMessage());
            return skipped("candidate-error:" + ex.getMessage());
        }
    }

    private RuntimeMemoryCandidateResult tryCreateFromLlmExtraction(String tenantId,
                                                                     Map<String, Object> metadata,
                                                                     EmbedSessionEntity session,
                                                                     String globalUserId,
                                                                     String externalUserId,
                                                                     String resolvedUserId,
                                                                     RuntimeMemoryCandidateIdentity identity,
                                                                     String userMessage,
                                                                     String assistantReply,
                                                                     String traceId,
                                                                     String modelInstanceId) {
        try {
            RuntimeMemoryExtractionRequest extractionRequest = RuntimeMemoryExtractionRequest.builder()
                    .modelInstanceId(modelInstanceId)
                    .tenantId(tenantId)
                    .projectCode(firstText(
                            stringValue(metadata == null ? null : metadata.get("projectCode")),
                            session == null ? null : session.getProjectCode()))
                    .projectId(longValue(metadata == null ? null : metadata.get("projectId")))
                    .userId(resolvedUserId)
                    .sessionId(session == null ? null : session.getSessionId())
                    .agentId(firstText(
                            identity.agentId(),
                            stringValue(metadata == null ? null : metadata.get("agentId")),
                            session == null ? null : session.getAgentId()))
                    .agentKey(firstText(
                            identity.agentKey(),
                            stringValue(metadata == null ? null : metadata.get("agentKey"))))
                    .workflowId(firstText(
                            identity.workflowId(),
                            stringValue(metadata == null ? null : metadata.get("workflowId"))))
                    .workflowKey(firstText(
                            identity.workflowKey(),
                            stringValue(metadata == null ? null : metadata.get("workflowKey"))))
                    .pageInstanceId(firstText(
                            stringValue(metadata == null ? null : metadata.get("pageInstanceId")),
                            session == null ? null : session.getPageInstanceId()))
                    .origin(firstText(
                            stringValue(metadata == null ? null : metadata.get("origin")),
                            session == null ? null : session.getOrigin()))
                    .traceId(traceId)
                    .userMessage(userMessage)
                    .assistantReply(assistantReply)
                    .build();
            List<RuntimeMemoryExtraction> extracted = memoryExtractor.extract(extractionRequest);
            if (extracted == null || extracted.isEmpty()) {
                return skipped("llm-no-candidates");
            }
            return createExtractedCandidates(extracted, tenantId, metadata, session, globalUserId,
                    externalUserId, resolvedUserId, identity, traceId, modelInstanceId);
        } catch (Exception ex) {
            log.warn("[RuntimeMemoryCandidate] LLM extraction failed, fallback if explicit intent: sessionId={}, reason={}",
                    session == null ? null : session.getSessionId(), ex.getMessage());
            return skipped("llm-error:" + ex.getMessage());
        }
    }

    private RuntimeMemoryCandidateResult createExtractedCandidates(List<RuntimeMemoryExtraction> extracted,
                                                                    String tenantId,
                                                                    Map<String, Object> metadata,
                                                                    EmbedSessionEntity session,
                                                                    String globalUserId,
                                                                    String externalUserId,
                                                                    String resolvedUserId,
                                                                    RuntimeMemoryCandidateIdentity identity,
                                                                    String traceId,
                                                                    String modelInstanceId) {
        List<Long> ids = new ArrayList<>();
        for (RuntimeMemoryExtraction extraction : extracted) {
            if (extraction == null || !StringUtils.hasText(extraction.getContent())) {
                continue;
            }
            ActiveMemoryCheck memoryCheck = activeMemoryCheck(tenantId, metadata, session, resolvedUserId, identity,
                    traceId, extraction.getCandidateType(), extraction.getContent());
            if (memoryCheck.alreadyRemembered()) {
                continue;
            }
            ContextMemoryCandidateCreateRequest request = baseRequest(
                    tenantId, metadata, session, globalUserId, externalUserId, resolvedUserId, identity, traceId);
            request.setCandidateType((extraction.getCandidateType() == null
                    ? ContextMemoryCandidateType.NOTE
                    : extraction.getCandidateType()).name());
            request.setTitle(extraction.getTitle());
            request.setContent(extraction.getContent().trim());
            request.setSummary(extraction.getSummary());
            request.setReason(firstText(extraction.getReason(), "LLM memory extraction"));
            request.setConfidence(extraction.getConfidence());
            request.setMetadataJson(extractionMetadata("LLM", modelInstanceId, memoryCheck.relatedHits()));
            ContextMemoryCandidateResponse created = candidateService.createRuntimeUserCandidateFromInteraction(request);
            ids.add(created.getId());
        }
        if (ids.isEmpty()) {
            return skipped("already-remembered");
        }
        return RuntimeMemoryCandidateResult.builder()
                .created(true)
                .candidateId(ids.get(0))
                .candidateIds(List.copyOf(ids))
                .createdCount(ids.size())
                .extractionMode("LLM")
                .build();
    }

    private RuntimeMemoryCandidateResult createRuleCandidate(String tenantId,
                                                              Map<String, Object> metadata,
                                                              EmbedSessionEntity session,
                                                              String globalUserId,
                                                              String externalUserId,
                                                              String resolvedUserId,
                                                              RuntimeMemoryCandidateIdentity identity,
                                                              String userMessage,
                                                              String traceId,
                                                              String extractionMode) {
        ContextMemoryCandidateCreateRequest request = baseRequest(
                tenantId, metadata, session, globalUserId, externalUserId, resolvedUserId, identity, traceId);
        request.setCandidateType(inferCandidateType(userMessage).name());
        ActiveMemoryCheck memoryCheck = activeMemoryCheck(tenantId, metadata, session, resolvedUserId, identity,
                traceId, inferCandidateType(userMessage), userMessage);
        if (memoryCheck.alreadyRemembered()) {
            return skipped("already-remembered");
        }
        request.setContent(userMessage.trim());
        request.setSummary(null);
        request.setReason("Explicit user memory intent detected");
        request.setMetadataJson(extractionMetadata(extractionMode, null, memoryCheck.relatedHits()));
        ContextMemoryCandidateResponse created = candidateService.createRuntimeUserCandidateFromInteraction(request);
        return RuntimeMemoryCandidateResult.builder()
                .created(true)
                .candidateId(created.getId())
                .candidateIds(List.of(created.getId()))
                .createdCount(1)
                .extractionMode(extractionMode)
                .build();
    }

    private ContextMemoryCandidateCreateRequest baseRequest(String tenantId,
                                                            Map<String, Object> metadata,
                                                            EmbedSessionEntity session,
                                                            String globalUserId,
                                                            String externalUserId,
                                                            String resolvedUserId,
                                                            RuntimeMemoryCandidateIdentity identity,
                                                            String traceId) {
        ContextMemoryCandidateCreateRequest request = new ContextMemoryCandidateCreateRequest();
        request.setTenantId(tenantId);
        request.setProjectId(longValue(metadata == null ? null : metadata.get("projectId")));
        request.setProjectCode(firstText(
                stringValue(metadata == null ? null : metadata.get("projectCode")),
                session == null ? null : session.getProjectCode()));
        request.setSourceType(ContextSourceType.USER_MESSAGE.name());
        request.setSourceRef(traceId);
        request.setTraceId(traceId);
        request.setSessionId(session == null ? null : session.getSessionId());
        request.setGlobalUserId(globalUserId);
        request.setExternalUserId(externalUserId);
        request.setUserId(resolvedUserId);
        request.setAgentId(firstText(
                identity.agentId(),
                stringValue(metadata == null ? null : metadata.get("agentId")),
                session == null ? null : session.getAgentId()));
        request.setAgentKey(firstText(
                identity.agentKey(),
                stringValue(metadata == null ? null : metadata.get("agentKey"))));
        request.setWorkflowId(firstText(
                identity.workflowId(),
                stringValue(metadata == null ? null : metadata.get("workflowId"))));
        request.setWorkflowKey(firstText(
                identity.workflowKey(),
                stringValue(metadata == null ? null : metadata.get("workflowKey"))));
        request.setPageInstanceId(firstText(
                stringValue(metadata == null ? null : metadata.get("pageInstanceId")),
                session == null ? null : session.getPageInstanceId()));
        request.setOrigin(firstText(
                stringValue(metadata == null ? null : metadata.get("origin")),
                session == null ? null : session.getOrigin()));
        request.setProposedBy(resolvedUserId);
        return request;
    }

    private ActiveMemoryCheck activeMemoryCheck(String tenantId,
                                                Map<String, Object> metadata,
                                                EmbedSessionEntity session,
                                                String resolvedUserId,
                                                RuntimeMemoryCandidateIdentity identity,
                                                String traceId,
                                                ContextMemoryCandidateType candidateType,
                                                String content) {
        if (!StringUtils.hasText(content)) {
            return ActiveMemoryCheck.empty();
        }
        try {
            String expectedItemType = mapCandidateTypeToItemType(candidateType);
            ContextQueryRequest query = new ContextQueryRequest();
            query.setTenantId(tenantId);
            query.setProjectId(longValue(metadata == null ? null : metadata.get("projectId")));
            query.setProjectCode(firstText(
                    stringValue(metadata == null ? null : metadata.get("projectCode")),
                    session == null ? null : session.getProjectCode()));
            query.setMemoryLane(MemoryLane.RUNTIME_USER.name());
            query.setQuery(content.trim());
            query.setRetrievalMode("HYBRID");
            query.setItemTypes(List.of(expectedItemType));
            query.setUserId(resolvedUserId);
            query.setSessionId(session == null ? null : session.getSessionId());
            query.setAgentId(firstText(
                    identity.agentId(),
                    stringValue(metadata == null ? null : metadata.get("agentId")),
                    session == null ? null : session.getAgentId()));
            query.setWorkflowId(firstText(
                    identity.workflowId(),
                    stringValue(metadata == null ? null : metadata.get("workflowId"))));
            query.setPageInstanceId(firstText(
                    stringValue(metadata == null ? null : metadata.get("pageInstanceId")),
                    session == null ? null : session.getPageInstanceId()));
            query.setTraceId(traceId);
            query.setActorType("USER");
            query.setActorId(resolvedUserId);
            query.setTopK(20);
            List<ContextSearchResult> hits = retrievalService.search(query);
            if (hits == null || hits.isEmpty()) {
                return ActiveMemoryCheck.empty();
            }
            String normalized = normalizeContent(content);
            List<ContextSearchResult> related = new ArrayList<>();
            for (ContextSearchResult hit : hits) {
                ContextItemResponse item = hit == null ? null : hit.getItem();
                if (item == null) {
                    continue;
                }
                if (!expectedItemType.equalsIgnoreCase(item.getItemType())) {
                    continue;
                }
                if (normalized.equals(normalizeContent(item.getContent()))) {
                    return new ActiveMemoryCheck(true, List.of());
                }
                if (related.size() < 3) {
                    related.add(hit);
                }
            }
            return new ActiveMemoryCheck(false, related);
        } catch (Exception ex) {
            log.warn("[RuntimeMemoryCandidate] already-remembered check skipped: sessionId={}, reason={}",
                    session == null ? null : session.getSessionId(), ex.getMessage());
        }
        return ActiveMemoryCheck.empty();
    }

    private String mapCandidateTypeToItemType(ContextMemoryCandidateType candidateType) {
        ContextMemoryCandidateType safeType = candidateType == null ? ContextMemoryCandidateType.NOTE : candidateType;
        return switch (safeType) {
            case PREFERENCE -> "PREFERENCE";
            case PAGE_CONTEXT -> "PAGE_CONTEXT";
            case WORKFLOW_CONTEXT -> "WORKFLOW_CONTEXT";
            case API_CONTEXT -> "API_CONTRACT";
            case RULE -> "RULE";
            case FACT -> "FACT";
            case NOTE -> "NOTE";
        };
    }

    boolean hasExplicitMemoryIntent(String message) {
        if (!StringUtils.hasText(message)) {
            return false;
        }
        String normalized = message.trim();
        return MEMORY_INTENT_TRIGGERS.stream().anyMatch(normalized::contains);
    }

    /**
     * Rules remain conservative; richer typing is handled by the LLM extractor.
     */
    ContextMemoryCandidateType inferCandidateType(String message) {
        if (message.contains("偏好") || message.contains("喜欢")) {
            return ContextMemoryCandidateType.PREFERENCE;
        }
        return ContextMemoryCandidateType.PREFERENCE;
    }

    private RuntimeMemoryCandidateResult skipped(String reason) {
        return RuntimeMemoryCandidateResult.builder()
                .created(false)
                .candidateIds(List.of())
                .createdCount(0)
                .skippedReason(reason)
                .build();
    }

    private String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return StringUtils.hasText(text) ? text : null;
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value != null && StringUtils.hasText(String.valueOf(value))) {
            try {
                return Long.parseLong(String.valueOf(value).trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String extractionMetadata(String extractionMode, String modelInstanceId, List<ContextSearchResult> relatedHits) {
        StringBuilder json = new StringBuilder("{\"extractionMode\":\"")
                .append(escapeJson(extractionMode))
                .append("\"");
        if (StringUtils.hasText(modelInstanceId)) {
            json.append(",\"modelInstanceId\":\"").append(escapeJson(modelInstanceId)).append("\"");
        }
        json.append(",\"extractorVersion\":\"context-memory-extraction-v1\"");
        appendQualitySignals(json, relatedHits);
        json.append("}");
        return json.toString();
    }

    private void appendQualitySignals(StringBuilder json, List<ContextSearchResult> relatedHits) {
        if (relatedHits == null || relatedHits.isEmpty()) {
            return;
        }
        json.append(",\"qualitySignals\":{\"reviewHint\":\"related-active-memory\",\"relatedActiveItems\":[");
        for (int i = 0; i < relatedHits.size(); i++) {
            ContextSearchResult hit = relatedHits.get(i);
            ContextItemResponse item = hit == null ? null : hit.getItem();
            if (item == null) {
                continue;
            }
            if (i > 0) {
                json.append(",");
            }
            json.append("{\"itemId\":").append(item.getId() == null ? "null" : item.getId())
                    .append(",\"itemType\":\"").append(escapeJson(item.getItemType())).append("\"")
                    .append(",\"title\":\"").append(escapeJson(item.getTitle())).append("\"")
                    .append(",\"rankScore\":").append(hit.getRankScore())
                    .append(",\"hitReason\":\"").append(escapeJson(hit.getHitReason())).append("\"")
                    .append(",\"scoreBreakdown\":\"").append(escapeJson(hit.getScoreBreakdown())).append("\"}");
        }
        json.append("]}");
    }

    private String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String normalizeContent(String content) {
        if (!StringUtils.hasText(content)) {
            return "";
        }
        return content.trim().replaceAll("\\s+", " ");
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private record ActiveMemoryCheck(boolean alreadyRemembered, List<ContextSearchResult> relatedHits) {
        private static ActiveMemoryCheck empty() {
            return new ActiveMemoryCheck(false, List.of());
        }
    }
}
