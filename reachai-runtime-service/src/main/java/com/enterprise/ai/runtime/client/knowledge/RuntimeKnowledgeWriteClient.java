package com.enterprise.ai.runtime.client.knowledge;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

/** Runtime → Knowledge write for workflow KNOWLEDGE_WRITE nodes (HMAC signed, project scoped). */
@FeignClient(
        name = "reachai-knowledge-write",
        url = "${services.knowledge-service.url:http://localhost:18602}",
        configuration = RuntimeKnowledgeWriteAuthFeignConfig.class
)
public interface RuntimeKnowledgeWriteClient {

    @PostMapping("/ai/internal/knowledge/workflow-write/text")
    WriteResult writeText(@RequestBody WriteTextRequest request);

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record WriteTextRequest(String knowledgeBaseCode, String text, String title, String projectCode,
                            String idempotencyKey) {
    }

    record WriteResult(int code, String message, Map<String, Object> data) {
    }
}
