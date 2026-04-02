package com.enterprise.ai.controller;

import com.enterprise.ai.domain.dto.ApiResult;
import com.enterprise.ai.domain.dto.ChunkVO;
import com.enterprise.ai.service.KnowledgeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 文件管理 Controller — 提供文件级别的查看、删除、重新解析
 */
@RestController
@RequestMapping("/file")
@RequiredArgsConstructor
public class FileController {

    private final KnowledgeService knowledgeService;

    /**
     * GET /ai/file/{fileId}/chunks — 获取文件的 chunk 列表
     */
    @GetMapping("/{fileId}/chunks")
    public ApiResult<List<ChunkVO>> getChunks(@PathVariable String fileId) {
        return ApiResult.ok(knowledgeService.getChunksByFileId(fileId));
    }

    /**
     * DELETE /ai/file/{fileId} — 删除文件及其全部关联数据（chunk + 向量）
     */
    @DeleteMapping("/{fileId}")
    public ApiResult<Void> deleteFile(@PathVariable String fileId) {
        knowledgeService.deleteFileById(fileId);
        return ApiResult.ok();
    }

    /**
     * POST /ai/file/{fileId}/reparse — 使用知识库最新配置重新解析文件
     */
    @PostMapping("/{fileId}/reparse")
    public ApiResult<Void> reparseFile(@PathVariable String fileId) {
        knowledgeService.reparseFile(fileId);
        return ApiResult.ok();
    }
}
