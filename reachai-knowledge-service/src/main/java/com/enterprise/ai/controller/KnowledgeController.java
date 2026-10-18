package com.enterprise.ai.controller;

import com.enterprise.ai.common.dto.ApiResult;
import com.enterprise.ai.domain.dto.ChunkUpdateRequest;
import com.enterprise.ai.domain.dto.ChunkVO;
import com.enterprise.ai.domain.dto.FileInfoVO;
import com.enterprise.ai.domain.dto.KbConfigRequest;
import com.enterprise.ai.domain.dto.KnowledgeBaseRequest;
import com.enterprise.ai.domain.dto.KnowledgeBaseVO;
import com.enterprise.ai.domain.dto.KnowledgeHitLogDTO;
import com.enterprise.ai.domain.dto.KnowledgeImportRequest;
import com.enterprise.ai.domain.dto.KnowledgeOpsDashboardVO;
import com.enterprise.ai.domain.dto.KnowledgeStatsVO;
import com.enterprise.ai.service.KnowledgeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/knowledge")
@RequiredArgsConstructor
public class KnowledgeController {

    private final KnowledgeService knowledgeService;

    // ==================== 知识库 CRUD ====================

    @GetMapping("/base/list")
    public ApiResult<List<KnowledgeBaseVO>> list() {
        return ApiResult.ok(knowledgeService.listAll());
    }

    @PostMapping("/base")
    public ApiResult<Void> create(@Valid @RequestBody KnowledgeBaseRequest request) {
        knowledgeService.create(request);
        return ApiResult.ok();
    }

    @PutMapping("/base")
    public ApiResult<Void> update(@Valid @RequestBody KnowledgeBaseRequest request) {
        knowledgeService.update(request);
        return ApiResult.ok();
    }

    @DeleteMapping("/base/{code}")
    public ApiResult<Void> deleteByCode(@PathVariable String code) {
        knowledgeService.deleteByCode(code);
        return ApiResult.ok();
    }

    // ==================== V2: 知识库详情 & 配置 ====================

    /**
     * GET /ai/kb/{kbCode}/files — 获取知识库下的文件列表
     */
    @GetMapping("/kb/{kbCode}/files")
    public ApiResult<List<FileInfoVO>> getFiles(@PathVariable String kbCode) {
        return ApiResult.ok(knowledgeService.getFilesByKbCode(kbCode));
    }

    @GetMapping("/kb/{kbCode}/stats")
    public ApiResult<KnowledgeStatsVO> getStats(@PathVariable String kbCode) {
        return ApiResult.ok(knowledgeService.getStats(kbCode));
    }

    @GetMapping("/kb/{kbCode}/ops-dashboard")
    public ApiResult<KnowledgeOpsDashboardVO> getOpsDashboard(@PathVariable String kbCode) {
        return ApiResult.ok(knowledgeService.getOpsDashboard(kbCode));
    }

    @GetMapping("/kb/{kbCode}/chunks")
    public ApiResult<List<ChunkVO>> listChunks(@PathVariable String kbCode,
                                               @RequestParam(required = false) String keyword,
                                               @RequestParam(required = false) Integer enabled,
                                               @RequestParam(required = false) Integer limit) {
        return ApiResult.ok(knowledgeService.listChunks(kbCode, keyword, enabled, limit));
    }

    @GetMapping("/kb/{kbCode}/hit-logs")
    public ApiResult<List<KnowledgeHitLogDTO>> listHitLogs(@PathVariable String kbCode,
                                                           @RequestParam(required = false) Integer limit,
                                                           @RequestParam(required = false) Boolean lowConfidenceOnly,
                                                           @RequestParam(required = false) String source) {
        return ApiResult.ok(knowledgeService.listHitLogs(kbCode, limit, lowConfidenceOnly, source));
    }

    /**
     * PUT /ai/kb/{kbCode}/config — 更新知识库 chunk 策略配置
     */
    @PutMapping("/kb/{kbCode}/config")
    public ApiResult<Void> updateConfig(@PathVariable String kbCode,
                                        @Valid @RequestBody KbConfigRequest request) {
        knowledgeService.updateKbConfig(kbCode, request);
        return ApiResult.ok();
    }

    // ==================== Chunk 预览 ====================

    @PutMapping("/chunks/{chunkId}")
    public ApiResult<ChunkVO> updateChunk(@PathVariable Long chunkId,
                                          @RequestBody ChunkUpdateRequest request) {
        return ApiResult.ok(knowledgeService.updateChunk(chunkId, request));
    }

    @PostMapping("/chunks/{chunkId}/toggle")
    public ApiResult<ChunkVO> toggleChunk(@PathVariable Long chunkId,
                                          @RequestParam(defaultValue = "1") Integer enabled) {
        return ApiResult.ok(knowledgeService.toggleChunk(chunkId, enabled));
    }

    @PostMapping("/chunks/{chunkId}/reembed")
    public ApiResult<Void> reembedChunk(@PathVariable Long chunkId) {
        knowledgeService.reembedChunk(chunkId);
        return ApiResult.ok();
    }

    // ==================== 知识数据管理（保留原有） ====================

    @PostMapping("/import")
    public ApiResult<Void> importChunks(@Valid @RequestBody KnowledgeImportRequest request) {
        knowledgeService.importChunks(request);
        return ApiResult.ok();
    }

    @DeleteMapping("/file")
    public ApiResult<Void> deleteFile(@RequestParam String knowledgeBaseCode,
                                      @RequestParam String fileId) {
        knowledgeService.deleteByFileId(knowledgeBaseCode, fileId);
        return ApiResult.ok();
    }
}
