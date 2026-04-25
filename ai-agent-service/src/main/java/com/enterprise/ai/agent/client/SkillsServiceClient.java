package com.enterprise.ai.agent.client;

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
 * ai-skills-service Feign Client — RAG 检索与知识问答。
 * <p>
 * 对应 ai-skills-service 的 /ai/rag/* 和 /ai/retrieval/* 接口。
 */
@FeignClient(
        name = "ai-skills-service",
        url = "${services.skills-service.url:http://localhost:8602}",
        path = "/ai"
)
public interface SkillsServiceClient {

    @PostMapping("/rag/query")
    RagResult ragQuery(@RequestBody RagQueryRequest request);

    @PostMapping("/retrieval/test")
    RetrievalResult retrievalTest(@RequestBody RetrievalRequest request);

    // ==================== Request / Response DTOs ====================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    class RagQueryRequest {
        private String question;
        private String userId;
        private List<String> knowledgeBaseCodes;
        private Integer topK;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    class RagResult {
        private int code;
        private String message;
        private RagResultData data;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    class RagResultData {
        private String answer;
        private List<Map<String, Object>> references;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    class RetrievalRequest {
        private String question;
        private String knowledgeBaseCode;
        private Integer topK;
        private Double scoreThreshold;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    class RetrievalResult {
        private int code;
        private String message;
        private Object data;
    }
}
