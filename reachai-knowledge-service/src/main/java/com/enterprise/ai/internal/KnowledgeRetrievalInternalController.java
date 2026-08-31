package com.enterprise.ai.internal;

import com.enterprise.ai.common.dto.ApiResult;
import com.enterprise.ai.retrieval.KnowledgeRetrievalCore;
import com.enterprise.ai.retrieval.KnowledgeRetrievalCoreRequest;
import com.enterprise.ai.retrieval.KnowledgeRetrievalCoreResponse;
import com.enterprise.ai.security.PermissionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Internal retrieval API for Runtime Workflow KNOWLEDGE_RETRIEVAL nodes.
 * Calls {@link KnowledgeRetrievalCore} with Runtime DTOs — never RetrievalTestRequest/retrievalTest.
 */
@RestController
@RequestMapping("/internal/knowledge")
@RequiredArgsConstructor
public class KnowledgeRetrievalInternalController {

    private static final int MAX_TOP_K = 20;
    private static final int MAX_CONTENT_CHARS = 4_000;
    private static final int MAX_TOTAL_CONTENT_CHARS = 24_000;
    private static final Set<String> UPSTREAM_DIAGNOSTIC_KEYS = Set.of(
            "knowledgeBaseCount",
            "failedKnowledgeBaseCount",
            "vectorRawCandidateCount",
            "vectorAcceptedCandidateCount",
            "keywordRawCandidateCount",
            "keywordAcceptedCandidateCount",
            "preMergeCandidateCount",
            "mergedCandidateCount",
            "rerankedCandidateCount",
            "scoreAcceptedCandidateCount",
            "scoreFilteredCandidateCount",
            "topKTruncatedCandidateCount",
            "returnedCandidateCount",
            "retrievalStageMs",
            "rerankStageMs",
            "totalCostMs");

    private final KnowledgeRetrievalCore knowledgeRetrievalCore;
    private final PermissionService permissionService;

    @PostMapping("/retrieval/query")
    public ApiResult<RetrievalData> retrieve(@Valid @RequestBody RetrievalRequest request) {
        String userId = request.userId().trim();
        List<String> accessibleFileIds = permissionService.getAccessibleFileIds(userId);
        if (accessibleFileIds == null || accessibleFileIds.isEmpty()) {
            return ApiResult.ok(new RetrievalData(
                    request.query().trim(),
                    List.of(),
                    0,
                    "NO_EVIDENCE",
                    true,
                    contentDiagnostics(Map.of(), 0, 0, 0, 0, false)));
        }
        Set<String> allowedFiles = new HashSet<>(accessibleFileIds);

        int topK = request.topK() == null ? 5 : Math.max(1, Math.min(MAX_TOP_K, request.topK()));
        String searchMode = normalizeSearchMode(request.searchMode());
        boolean rerankEnabled = request.rerankEnabled() == null || Boolean.TRUE.equals(request.rerankEnabled());

        KnowledgeRetrievalCoreResponse response = knowledgeRetrievalCore.retrieve(KnowledgeRetrievalCoreRequest.builder()
                .query(request.query().trim())
                .knowledgeBaseCodes(request.knowledgeBaseCodes())
                .userId(userId)
                .topK(topK)
                .scoreThreshold(request.similarityThreshold())
                .searchMode(searchMode)
                .rerankEnabled(rerankEnabled)
                .recordHit(false)
                .accessibleFileIds(accessibleFileIds)
                .fileIdFilterExpression(permissionService.buildMilvusFilter(accessibleFileIds))
                .build());

        List<KnowledgeRetrievalCoreResponse.RetrievalItem> items = response == null || response.getItems() == null
                ? List.of()
                : response.getItems();
        List<KnowledgeRetrievalCoreResponse.RetrievalItem> allowedItems = items.stream()
                .filter(item -> item != null)
                .filter(item -> !StringUtils.hasText(item.getFileId()) || allowedFiles.contains(item.getFileId()))
                .toList();
        List<RetrievalHit> hits = new ArrayList<>();
        int totalContent = 0;
        int contentTruncatedCount = 0;
        int budgetOmittedHitCount = 0;
        boolean contentBudgetExhausted = false;
        for (int index = 0; index < allowedItems.size(); index++) {
            KnowledgeRetrievalCoreResponse.RetrievalItem item = allowedItems.get(index);
            String content = item.getContent() == null ? "" : item.getContent();
            boolean contentTruncated = false;
            if (content.length() > MAX_CONTENT_CHARS) {
                content = content.substring(0, MAX_CONTENT_CHARS);
                contentTruncated = true;
            }
            if (totalContent + content.length() > MAX_TOTAL_CONTENT_CHARS) {
                contentBudgetExhausted = true;
                budgetOmittedHitCount = allowedItems.size() - index;
                break;
            }
            if (contentTruncated) {
                contentTruncatedCount++;
            }
            totalContent += content.length();
            Map<String, Object> metadata = new LinkedHashMap<>();
            if (StringUtils.hasText(item.getFileId())) {
                metadata.put("fileId", item.getFileId());
            }
            metadata.put("searchMode", searchMode);
            metadata.put("rerankApplied", rerankEnabled && item.getRerankScore() != null);
            hits.add(new RetrievalHit(
                    item.getChunkId(),
                    item.getChunkId(),
                    item.getKnowledgeBaseCode(),
                    item.getFileName(),
                    item.getFileName(),
                    content,
                    item.getScore(),
                    metadata));
            if (hits.size() >= topK) {
                break;
            }
        }
        boolean empty = hits.isEmpty();
        Map<String, Object> diagnostics = contentDiagnostics(
                response == null ? Map.of() : response.getDiagnostics(),
                hits.size(),
                totalContent,
                contentTruncatedCount,
                budgetOmittedHitCount,
                contentBudgetExhausted);
        return ApiResult.ok(new RetrievalData(
                request.query().trim(),
                hits,
                hits.size(),
                empty ? "NO_EVIDENCE" : "HIT",
                empty,
                diagnostics));
    }

    private static Map<String, Object> contentDiagnostics(Map<String, Object> upstream,
                                                          int returnedHitCount,
                                                          int returnedContentChars,
                                                          int contentTruncatedCount,
                                                          int budgetOmittedHitCount,
                                                          boolean contentBudgetExhausted) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        if (upstream != null) {
            upstream.forEach((key, value) -> {
                if (UPSTREAM_DIAGNOSTIC_KEYS.contains(key)
                        && (value instanceof Number || value instanceof Boolean)) {
                    diagnostics.put(key, value);
                }
            });
        }
        diagnostics.put("returnedHitCount", returnedHitCount);
        diagnostics.put("returnedContentChars", returnedContentChars);
        diagnostics.put("contentTruncatedCount", contentTruncatedCount);
        diagnostics.put("budgetOmittedHitCount", budgetOmittedHitCount);
        diagnostics.put("contentBudgetExhausted", contentBudgetExhausted);
        diagnostics.put("perHitContentLimit", MAX_CONTENT_CHARS);
        diagnostics.put("totalContentLimit", MAX_TOTAL_CONTENT_CHARS);
        return diagnostics;
    }

    private static String normalizeSearchMode(String raw) {
        String mode = StringUtils.hasText(raw) ? raw.trim().toLowerCase(Locale.ROOT) : "hybrid";
        return switch (mode) {
            case "vector", "keyword", "hybrid" -> mode;
            default -> "hybrid";
        };
    }

    public record RetrievalRequest(
            @NotBlank String query,
            @NotEmpty List<String> knowledgeBaseCodes,
            @NotBlank String userId,
            Integer topK,
            Float similarityThreshold,
            String searchMode,
            Boolean rerankEnabled
    ) {
    }

    public record RetrievalData(
            String query,
            List<RetrievalHit> hits,
            Integer hitCount,
            String outcome,
            Boolean empty,
            Map<String, Object> diagnostics
    ) {
    }

    public record RetrievalHit(
            String id,
            String chunkId,
            String knowledgeBaseCode,
            String title,
            String source,
            String content,
            Float score,
            Map<String, Object> metadata
    ) {
    }
}
