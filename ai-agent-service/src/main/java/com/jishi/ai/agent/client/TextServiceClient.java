package com.jishi.ai.agent.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

/**
 * ai-text-service Feign Client — 用于 RAG 检索与知识问答。
 * 直连 ai-text-service 的 /ai/rag/query 和 /ai/retrieval/test 接口。
 */
@FeignClient(
        name = "ai-text-service",
        url = "${services.text-service.url:http://localhost:8080}",
        path = "/ai"
)
public interface TextServiceClient {

    @PostMapping("/rag/query")
    Map<String, Object> ragQuery(@RequestBody Map<String, Object> request);

    @PostMapping("/retrieval/test")
    Map<String, Object> retrievalTest(@RequestBody Map<String, Object> request);
}
