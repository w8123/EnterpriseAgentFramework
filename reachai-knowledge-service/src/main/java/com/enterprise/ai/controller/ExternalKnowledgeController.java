package com.enterprise.ai.controller;

import com.enterprise.ai.common.dto.ApiResult;
import com.enterprise.ai.domain.dto.ExternalKnowledgeConnectionRequest;
import com.enterprise.ai.external.ExternalKnowledgeConnection;
import com.enterprise.ai.external.ExternalKnowledgeException;
import com.enterprise.ai.external.ExternalKnowledgeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * External knowledge bases (RAGFlow / ima / Dify). Reached only through Control, which checks knowledge write on the
 * named base (or on the requested scope for a fresh draft) before forwarding a test or a connection change.
 */
@RestController
@RequestMapping("/knowledge")
@RequiredArgsConstructor
public class ExternalKnowledgeController {
    private final ExternalKnowledgeService external;

    /** 可选的外部知识库类型。 */
    @GetMapping("/external/providers")
    public ApiResult<List<Map<String, Object>>> providers() {
        return ApiResult.ok(external.providers());
    }

    /** 测试连接并列出远端可选的库；密钥留空时沿用 kbCode 已保存的密钥。 */
    @PostMapping("/external/test")
    public ApiResult<List<ExternalKnowledgeConnection.RemoteDataset>> test(@RequestBody ExternalKnowledgeConnectionRequest request) {
        try {
            return ApiResult.ok(external.testConnection(request));
        } catch (ExternalKnowledgeException | IllegalArgumentException failure) {
            return ApiResult.fail(failure.getMessage());
        }
    }

    /** 修改外部知识库的连接（地址、密钥、选中的远端库）。 */
    @PutMapping("/base/{code}/external")
    public ApiResult<Void> update(@PathVariable String code, @RequestBody ExternalKnowledgeConnectionRequest request) {
        try {
            external.updateConnection(code, request);
            return ApiResult.ok();
        } catch (ExternalKnowledgeException | IllegalArgumentException failure) {
            return ApiResult.fail(failure.getMessage());
        }
    }
}
