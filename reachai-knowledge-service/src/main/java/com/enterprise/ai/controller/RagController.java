package com.enterprise.ai.controller;

import com.enterprise.ai.common.dto.ApiResult;
import com.enterprise.ai.domain.dto.RagRequest;
import com.enterprise.ai.domain.dto.RagResponse;
import com.enterprise.ai.rag.RagService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/rag")
@RequiredArgsConstructor
public class RagController {

    private final RagService ragService;

    /**
     * POST /ai/rag/query
     * RAG 问答接口 — 检索知识库并生成答案
     */
    @PostMapping("/query")
    public ApiResult<RagResponse> query(@Valid @RequestBody RagRequest request) {
        RagResponse response = ragService.query(request);
        return ApiResult.ok(response);
    }
}
