package com.enterprise.ai.controller;

import com.enterprise.ai.common.dto.ApiResult;
import com.enterprise.ai.domain.dto.EmbeddingRequest;
import com.enterprise.ai.domain.dto.EmbeddingResponse;
import com.enterprise.ai.embedding.EmbeddingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/embedding")
@RequiredArgsConstructor
public class EmbeddingController {

    private final EmbeddingService embeddingService;

    /**
     * POST /ai/embedding/vectorize
     * 文本向量化接口 — 将文本列表转换为向量
     */
    @PostMapping("/vectorize")
    public ApiResult<EmbeddingResponse> vectorize(@Valid @RequestBody EmbeddingRequest request) {
        List<List<Float>> embeddings = embeddingService.embedBatch(request.getTexts());
        EmbeddingResponse response = new EmbeddingResponse();
        response.setEmbeddings(embeddings);
        response.setModel(embeddingService.getModelName());
        return ApiResult.ok(response);
    }
}
