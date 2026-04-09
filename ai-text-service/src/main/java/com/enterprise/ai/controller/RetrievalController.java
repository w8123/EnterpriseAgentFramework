package com.enterprise.ai.controller;

import com.enterprise.ai.common.dto.ApiResult;
import com.enterprise.ai.domain.dto.RetrievalTestRequest;
import com.enterprise.ai.domain.dto.RetrievalTestResponse;
import com.enterprise.ai.service.KnowledgeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 检索测试 Controller — 纯向量检索（不调用 LLM），用于测试 RAG 效果
 */
@RestController
@RequestMapping("/retrieval")
@RequiredArgsConstructor
public class RetrievalController {

    private final KnowledgeService knowledgeService;

    /**
     * POST /ai/retrieval/test — 检索测试
     */
    @PostMapping("/test")
    public ApiResult<RetrievalTestResponse> test(@Valid @RequestBody RetrievalTestRequest request) {
        RetrievalTestResponse response = knowledgeService.retrievalTest(request);
        return ApiResult.ok(response);
    }
}
