package com.enterprise.ai.retrieval;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Canonical production retrieval request shared by admin retrieval test and Runtime internal API.
 */
@Data
@Builder
public class KnowledgeRetrievalCoreRequest {

    private String query;
    private List<String> knowledgeBaseCodes;
    private Integer topK;
    private Float scoreThreshold;
    private String searchMode;
    private Boolean rerankEnabled;
    private Boolean directReturnEnabled;
    private Float directReturnThreshold;
    private Float vectorWeight;
    private Float keywordWeight;
    private Boolean recordHit;
    private String traceId;
    private String userId;
    private List<String> accessibleFileIds;
    private String fileIdFilterExpression;
}
