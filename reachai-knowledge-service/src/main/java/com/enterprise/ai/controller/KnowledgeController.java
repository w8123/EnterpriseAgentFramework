package com.enterprise.ai.controller;

import com.enterprise.ai.common.dto.ApiResult;
import com.enterprise.ai.domain.dto.ChunkUpdateRequest;
import com.enterprise.ai.domain.dto.ChunkVO;
import com.enterprise.ai.domain.dto.ChunkPreviewResponse;
import com.enterprise.ai.domain.dto.FileInfoVO;
import com.enterprise.ai.domain.dto.KbConfigRequest;
import com.enterprise.ai.domain.dto.KnowledgeBaseRequest;
import com.enterprise.ai.domain.dto.KnowledgeBaseVO;
import com.enterprise.ai.domain.dto.KnowledgeHitLogDTO;
import com.enterprise.ai.domain.dto.KnowledgeImportRequest;
import com.enterprise.ai.domain.dto.KnowledgeOpsDashboardVO;
import com.enterprise.ai.domain.dto.KnowledgeQuestionDTO;
import com.enterprise.ai.domain.dto.KnowledgeQuestionRequest;
import com.enterprise.ai.domain.dto.KnowledgeStatsVO;
import com.enterprise.ai.domain.dto.KnowledgeTagBatchRequest;
import com.enterprise.ai.domain.dto.KnowledgeTagDTO;
import com.enterprise.ai.domain.dto.KnowledgeTagRequest;
import com.enterprise.ai.domain.dto.KnowledgeTagStatsDTO;
import com.enterprise.ai.domain.dto.PipelineResult;
import com.enterprise.ai.pipeline.PipelineContext;
import com.enterprise.ai.service.KnowledgeService;
import com.enterprise.ai.service.PipelineImportService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/knowledge")
@RequiredArgsConstructor
public class KnowledgeController {

    private final KnowledgeService knowledgeService;
    private final PipelineImportService pipelineImportService;
    private final ObjectMapper objectMapper;

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
                                               @RequestParam(required = false) String tagKey,
                                               @RequestParam(required = false) String tagValue,
                                               @RequestParam(required = false) Integer limit) {
        return ApiResult.ok(knowledgeService.listChunks(kbCode, keyword, enabled, tagKey, tagValue, limit));
    }

    @GetMapping("/kb/{kbCode}/hit-logs")
    public ApiResult<List<KnowledgeHitLogDTO>> listHitLogs(@PathVariable String kbCode,
                                                           @RequestParam(required = false) Integer limit,
                                                           @RequestParam(required = false) Boolean lowConfidenceOnly) {
        return ApiResult.ok(knowledgeService.listHitLogs(kbCode, limit, lowConfidenceOnly));
    }

    @GetMapping("/kb/{kbCode}/tags")
    public ApiResult<List<KnowledgeTagDTO>> listTags(@PathVariable String kbCode,
                                                     @RequestParam(required = false) String targetType,
                                                     @RequestParam(required = false) String targetId) {
        return ApiResult.ok(knowledgeService.listTags(kbCode, targetType, targetId));
    }

    @GetMapping("/kb/{kbCode}/tag-stats")
    public ApiResult<List<KnowledgeTagStatsDTO>> listTagStats(@PathVariable String kbCode) {
        return ApiResult.ok(knowledgeService.listTagStats(kbCode));
    }

    @PostMapping("/kb/{kbCode}/tags")
    public ApiResult<KnowledgeTagDTO> createTag(@PathVariable String kbCode,
                                                @Valid @RequestBody KnowledgeTagRequest request) {
        return ApiResult.ok(knowledgeService.createTag(kbCode, request));
    }

    @PostMapping("/kb/{kbCode}/tags/batch")
    public ApiResult<List<KnowledgeTagDTO>> batchCreateTags(@PathVariable String kbCode,
                                                            @Valid @RequestBody KnowledgeTagBatchRequest request) {
        return ApiResult.ok(knowledgeService.batchCreateTags(kbCode, request));
    }

    @DeleteMapping("/kb/{kbCode}/tags/{tagId}")
    public ApiResult<Void> deleteTag(@PathVariable String kbCode, @PathVariable Long tagId) {
        knowledgeService.deleteTag(kbCode, tagId);
        return ApiResult.ok();
    }

    @GetMapping("/kb/{kbCode}/questions")
    public ApiResult<List<KnowledgeQuestionDTO>> listQuestions(@PathVariable String kbCode,
                                                               @RequestParam(required = false) Long chunkId) {
        return ApiResult.ok(knowledgeService.listQuestions(kbCode, chunkId));
    }

    @PostMapping("/kb/{kbCode}/questions")
    public ApiResult<KnowledgeQuestionDTO> createQuestion(@PathVariable String kbCode,
                                                          @Valid @RequestBody KnowledgeQuestionRequest request) {
        return ApiResult.ok(knowledgeService.createQuestion(kbCode, request));
    }

    @DeleteMapping("/kb/{kbCode}/questions/{questionId}")
    public ApiResult<Void> deleteQuestion(@PathVariable String kbCode, @PathVariable Long questionId) {
        knowledgeService.deleteQuestion(kbCode, questionId);
        return ApiResult.ok();
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

    @PostMapping("/preview")
    public ApiResult<ChunkPreviewResponse> preview(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "chunkStrategy", defaultValue = "fixed_length") String chunkStrategy,
            @RequestParam(value = "chunkSize", defaultValue = "500") Integer chunkSize,
            @RequestParam(value = "chunkOverlap", defaultValue = "50") Integer chunkOverlap) {
        ChunkPreviewResponse response = knowledgeService.previewChunks(file, chunkStrategy, chunkSize, chunkOverlap);
        return ApiResult.ok(response);
    }

    // ==================== 文件入库（Pipeline） ====================

    @PostMapping("/import/file")
    public ApiResult<PipelineResult> importFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam("knowledgeBaseCode") String knowledgeBaseCode,
            @RequestParam(value = "chunkStrategy", defaultValue = "fixed_length") String chunkStrategy,
            @RequestParam(value = "chunkSize", defaultValue = "500") Integer chunkSize,
            @RequestParam(value = "chunkOverlap", defaultValue = "50") Integer chunkOverlap,
            @RequestParam(value = "extraParams", required = false) String extraParamsJson) {

        Map<String, Object> extraParams = new HashMap<>();
        if (extraParamsJson != null && !extraParamsJson.isBlank()) {
            try {
                extraParams = objectMapper.readValue(extraParamsJson, new TypeReference<>() {});
            } catch (Exception e) {
                return ApiResult.fail(400, "extraParams 格式错误，应为 JSON 对象字符串");
            }
        }

        String fileId = "file_" + System.currentTimeMillis();

        PipelineContext context = new PipelineContext();
        context.setFile(file);
        context.setFileId(fileId);
        context.setFileName(file.getOriginalFilename());
        context.setKnowledgeBaseCode(knowledgeBaseCode);
        context.setChunkStrategy(chunkStrategy);
        context.setChunkSize(chunkSize);
        context.setChunkOverlap(chunkOverlap);
        if (extraParams != null) {
            context.setExtraParams(extraParams);
        }

        PipelineResult result = pipelineImportService.execute(context);
        if ("FAILED".equals(result.getStatus())) {
            return ApiResult.fail(500, result.getErrorMessage());
        }
        return ApiResult.ok(result);
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
