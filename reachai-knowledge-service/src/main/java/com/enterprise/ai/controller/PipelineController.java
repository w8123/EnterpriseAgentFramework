package com.enterprise.ai.controller;

import com.enterprise.ai.common.dto.ApiResult;
import com.enterprise.ai.domain.dto.KnowledgeFileImportRequest;
import com.enterprise.ai.domain.dto.PipelineResult;
import com.enterprise.ai.pipeline.PipelineContext;
import com.enterprise.ai.service.PipelineImportService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * Pipeline 入库接口 — 通过文件上传触发完整的知识入库流水线。
 *
 * <h3>请求示例（multipart/form-data）</h3>
 * <pre>
 * POST /ai/pipeline/import
 * - file: (上传文件)
 * - knowledgeBaseCode: kb_contract
 * - fileId: file_20240101
 * - chunkStrategy: paragraph
 * - chunkSize: 500
 * - chunkOverlap: 50
 * </pre>
 */
@RestController
@RequestMapping("/pipeline")
@RequiredArgsConstructor
public class PipelineController {

    private final PipelineImportService pipelineImportService;

    /**
     * POST /ai/pipeline/import
     * 文件入库 — 上传文件并触发 Pipeline 流水线
     */
    @PostMapping("/import")
    public ApiResult<PipelineResult> importFile(
            @RequestParam("file") MultipartFile file,
            @Valid @ModelAttribute KnowledgeFileImportRequest request) {

        // 构建 Pipeline 上下文
        PipelineContext context = new PipelineContext();
        context.setFile(file);
        context.setFileId(request.getFileId());
        context.setFileName(file.getOriginalFilename());
        context.setKnowledgeBaseCode(request.getKnowledgeBaseCode());
        context.setChunkStrategy(request.getChunkStrategy());
        context.setChunkSize(request.getChunkSize() != null ? request.getChunkSize() : 500);
        context.setChunkOverlap(request.getChunkOverlap() != null ? request.getChunkOverlap() : 50);
        if (request.getExtraParams() != null) {
            context.setExtraParams(request.getExtraParams());
        }

        PipelineResult result = pipelineImportService.execute(context);

        if ("FAILED".equals(result.getStatus())) {
            return ApiResult.fail(500, result.getErrorMessage());
        }
        return ApiResult.ok(result);
    }
}
