package com.enterprise.ai.agent.platform.control.context;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ContextComposerService {

    private final ContextRetrievalService retrievalService;
    private final ContextEvidenceService evidenceService;
    private final ContextAuditService auditService;

    public ContextPackageResponse compose(ContextPackageComposeRequest request) {
        if (request == null || request.getQuery() == null) {
            throw new IllegalArgumentException("query is required");
        }
        ContextQueryRequest query = request.getQuery();
        List<ContextSearchResult> hits = retrievalService.search(query);

        int maxItems = request.getMaxItems() == null || request.getMaxItems() <= 0 ? 20 : request.getMaxItems();
        Integer tokenBudget = request.getTokenBudget();
        List<ContextSearchResult> trimmed = trimHits(hits, maxItems, tokenBudget);

        List<ContextSearchResult> projectMemory = new ArrayList<>();
        List<ContextSearchResult> userMemory = new ArrayList<>();
        List<ContextSearchResult> pageContext = new ArrayList<>();
        List<ContextSearchResult> workflowContext = new ArrayList<>();
        List<ContextSearchResult> apiContext = new ArrayList<>();
        List<ContextSearchResult> rules = new ArrayList<>();
        List<ContextSearchResult> evidenceSummary = new ArrayList<>();

        MemoryLane lane = MemoryLane.valueOf(query.getMemoryLane().trim().toUpperCase());
        for (ContextSearchResult hit : trimmed) {
            ContextItemType itemType = ContextItemType.valueOf(hit.getItem().getItemType());
            switch (itemType) {
                case PAGE_CONTEXT -> pageContext.add(hit);
                case WORKFLOW_CONTEXT -> workflowContext.add(hit);
                case API_CONTRACT -> apiContext.add(hit);
                case RULE -> rules.add(hit);
                // All remaining item types are bucketed by lane so no retrieved item is silently
                // dropped: PROJECT_DEV → projectMemory, RUNTIME_USER → userMemory. The retrieval
                // layer already guarantees lane isolation, so cross-lane mixing cannot happen here.
                default -> {
                    if (lane == MemoryLane.PROJECT_DEV) {
                        projectMemory.add(hit);
                    } else {
                        userMemory.add(hit);
                    }
                }
            }
            if (hit.getItem().getId() != null) {
                evidenceService.listEvidence(hit.getItem().getId(), query).stream()
                        .findFirst()
                        .ifPresent(evidence -> evidenceSummary.add(ContextSearchResult.builder()
                                .item(hit.getItem())
                                .rankScore(hit.getRankScore())
                                .hitReason("evidence:" + evidence.getEvidenceType())
                                .build()));
            }
        }

        auditService.record(ContextAuditEventType.INJECT, ContextAuditDecision.ALLOW,
                "context package composed items=" + trimmed.size(), query, null, null);

        return ContextPackageResponse.builder()
                .memoryLane(query.getMemoryLane())
                .tenantId(query.getTenantId())
                .projectCode(query.getProjectCode())
                .totalItems(hits.size())
                .truncatedCount(Math.max(0, hits.size() - trimmed.size()))
                .projectMemory(projectMemory)
                .userMemory(userMemory)
                .pageContext(pageContext)
                .workflowContext(workflowContext)
                .apiContext(apiContext)
                .rules(rules)
                .evidenceSummary(evidenceSummary)
                .build();
    }

    private List<ContextSearchResult> trimHits(List<ContextSearchResult> hits, int maxItems, Integer tokenBudget) {
        List<ContextSearchResult> trimmed = new ArrayList<>();
        int tokenUsed = 0;
        for (ContextSearchResult hit : hits) {
            if (trimmed.size() >= maxItems) {
                break;
            }
            int itemTokens = estimateTokens(hit.getItem());
            if (tokenBudget != null && tokenBudget > 0 && !trimmed.isEmpty() && tokenUsed + itemTokens > tokenBudget) {
                break;
            }
            trimmed.add(hit);
            tokenUsed += itemTokens;
        }
        return trimmed;
    }

    private int estimateTokens(ContextItemResponse item) {
        int titleLen = item.getTitle() == null ? 0 : item.getTitle().length();
        int contentLen = item.getContent() == null ? 0 : item.getContent().length();
        int summaryLen = item.getSummary() == null ? 0 : item.getSummary().length();
        return Math.max(1, (titleLen + contentLen + summaryLen) / 4);
    }
}
