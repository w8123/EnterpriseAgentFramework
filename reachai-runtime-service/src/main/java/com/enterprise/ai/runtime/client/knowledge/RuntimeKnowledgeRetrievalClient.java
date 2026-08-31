package com.enterprise.ai.runtime.client.knowledge;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;
import java.util.Map;

/**
 * Runtime → Knowledge internal retrieval contract (hits only, no cross-service table access).
 */
@FeignClient(
        name = "reachai-knowledge-service",
        url = "${services.knowledge-service.url:http://localhost:18602}"
)
public interface RuntimeKnowledgeRetrievalClient {

    @PostMapping("/internal/knowledge/retrieval/query")
    KnowledgeRetrievalResult retrieve(@RequestBody KnowledgeRetrievalRequest request);

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    class KnowledgeRetrievalRequest {
        private String query;
        private List<String> knowledgeBaseCodes;
        private String userId;
        private Integer topK;
        private Float similarityThreshold;
        private String searchMode;
        private Boolean rerankEnabled;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    class KnowledgeRetrievalResult {
        private int code;
        private String message;
        private KnowledgeRetrievalData data;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    class KnowledgeRetrievalData {
        private String query;
        private List<KnowledgeHit> hits;
        private Integer hitCount;
        /** HIT / NO_EVIDENCE; absent on older Knowledge deployments. */
        private String outcome;
        private Boolean empty;
        /** Non-sensitive counters/timings only; never query or evidence content. */
        private Map<String, Object> diagnostics;

        public KnowledgeRetrievalData(String query, List<KnowledgeHit> hits, Integer hitCount) {
            this.query = query;
            this.hits = hits;
            this.hitCount = hitCount;
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    class KnowledgeHit {
        private String id;
        private String chunkId;
        private String knowledgeBaseCode;
        private String title;
        private String source;
        private String content;
        private Float score;
        private Map<String, Object> metadata;
    }
}
