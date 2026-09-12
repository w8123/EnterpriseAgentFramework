package com.enterprise.ai.service.impl;

import com.enterprise.ai.domain.dto.*;
import com.enterprise.ai.domain.entity.KnowledgeBase;
import com.enterprise.ai.domain.vo.SimilarItem;
import com.enterprise.ai.repository.KnowledgeBaseLookup;
import com.enterprise.ai.retrieval.KnowledgeRetrievalEngine;
import com.enterprise.ai.service.KnowledgeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


import java.util.List;

@Service
@RequiredArgsConstructor
public class KnowledgeServiceImpl implements KnowledgeService {

    private final KnowledgeQuestionService questions;
    private final KnowledgeOperationsQuery operations;
    private final KnowledgeIndexWriteService indexWrites;
    private final KnowledgeBaseLookup knowledgeBaseLookup;
    private final KnowledgeRetrievalEngine retrievalEngine;
    private final KnowledgeFileDeletionService fileDeletion;
    private final KnowledgeBaseLifecycleService baseLifecycle;
    private final KnowledgeTagService tags;
    private final KnowledgeContentQuery contentQuery;
    private final KnowledgeChunkEditingService chunkEditing;

    @Override
    public void importChunks(KnowledgeImportRequest request) {
        indexWrites.importChunks(request);
    }

    @Override
    public List<KnowledgeBase> resolveKnowledgeBases(List<String> codes) {
        return knowledgeBaseLookup.resolveActive(codes);
    }

    @Override
    public void enrichFileName(List<SimilarItem> items) {
        contentQuery.enrichFileName(items);
    }

    @Override
    public void deleteByFileId(String knowledgeBaseCode, String fileId) {
        fileDeletion.deleteByFileId(knowledgeBaseCode, fileId);
    }

    // ==================== 知识库 CRUD ====================

    @Override
    public List<KnowledgeBaseVO> listAll() {
        return operations.listAll();
    }

    @Override
    public void create(KnowledgeBaseRequest request) {
        baseLifecycle.create(request);
    }

    @Override
    public void update(KnowledgeBaseRequest request) {
        baseLifecycle.update(request);
    }

    @Override
    public void deleteByCode(String code) {
        baseLifecycle.deleteByCode(code);
    }

    // ==================== 内容管理与检索 ====================

    @Override
    public List<FileInfoVO> getFilesByKbCode(String kbCode) {
        return contentQuery.getFilesByKbCode(kbCode);
    }

    @Override
    public List<ChunkVO> getChunksByFileId(String fileId) {
        return contentQuery.getChunksByFileId(fileId);
    }

    @Override
    public List<ChunkVO> listChunks(String kbCode, String keyword, Integer enabled, String tagKey, String tagValue, Integer limit) {
        return contentQuery.listChunks(kbCode, keyword, enabled, tagKey, tagValue, limit);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ChunkVO updateChunk(Long chunkId, ChunkUpdateRequest request) {
        return chunkEditing.updateChunk(chunkId, request);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ChunkVO toggleChunk(Long chunkId, Integer enabled) {
        return chunkEditing.toggleChunk(chunkId, enabled);
    }

    @Override
    public void reembedChunk(Long chunkId) {
        indexWrites.reembedChunk(chunkId);
    }

    @Override
    public List<KnowledgeHitLogDTO> listHitLogs(String kbCode, Integer limit, Boolean lowConfidenceOnly) {
        return operations.listHitLogs(kbCode, limit, lowConfidenceOnly);
    }

    @Override
    public KnowledgeOpsDashboardVO getOpsDashboard(String kbCode) {
        return operations.getOpsDashboard(kbCode);
    }

    @Override
    public void deleteFileById(String fileId) {
        fileDeletion.deleteFileById(fileId);
    }

    @Override
    public void updateKbConfig(String kbCode, KbConfigRequest request) {
        baseLifecycle.updateConfig(kbCode, request);
    }

    @Override
    public RetrievalTestResponse retrievalTest(RetrievalTestRequest request) {
        return retrievalEngine.execute(request);
    }

    // ==================== 统计、标签与问题管理 ====================

    @Override
    public KnowledgeStatsVO getStats(String kbCode) {
        return operations.getStats(kbCode);
    }

    @Override
    public List<KnowledgeTagDTO> listTags(String kbCode, String targetType, String targetId) {
        return tags.list(kbCode, targetType, targetId);
    }

    @Override
    public List<KnowledgeTagStatsDTO> listTagStats(String kbCode) {
        return tags.stats(kbCode);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeTagDTO createTag(String kbCode, KnowledgeTagRequest request) {
        return tags.create(kbCode, request);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<KnowledgeTagDTO> batchCreateTags(String kbCode, KnowledgeTagBatchRequest request) {
        return tags.createBatch(kbCode, request);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteTag(String kbCode, Long tagId) {
        tags.delete(kbCode, tagId);
    }

    @Override
    public List<KnowledgeQuestionDTO> listQuestions(String kbCode, Long chunkId) {
        return questions.list(kbCode, chunkId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeQuestionDTO createQuestion(String kbCode, KnowledgeQuestionRequest request) {
        return questions.create(kbCode, request);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteQuestion(String kbCode, Long questionId) {
        questions.delete(kbCode, questionId);
    }

}
