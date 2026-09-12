package com.enterprise.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.ai.domain.dto.KnowledgeQuestionDTO;
import com.enterprise.ai.domain.dto.KnowledgeQuestionRequest;
import com.enterprise.ai.domain.entity.KnowledgeBase;
import com.enterprise.ai.domain.entity.KnowledgeQuestion;
import com.enterprise.ai.repository.ChunkRepository;
import com.enterprise.ai.repository.KnowledgeBaseLookup;
import com.enterprise.ai.repository.KnowledgeBaseRepository;
import com.enterprise.ai.repository.KnowledgeQuestionRepository;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Objects;

/** Curated questions survive file retirement with their obsolete chunk reference detached. */
@Service
public class KnowledgeQuestionService {
    private final KnowledgeBaseRepository bases;
    private final ChunkRepository chunks;
    private final KnowledgeQuestionRepository questions;
    private final TransactionTemplate transactions;

    public KnowledgeQuestionService(KnowledgeBaseRepository bases, ChunkRepository chunks,
                                    KnowledgeQuestionRepository questions, PlatformTransactionManager manager) {
        this.bases = bases; this.chunks = chunks; this.questions = questions;
        transactions = new TransactionTemplate(manager);
    }

    public List<KnowledgeQuestionDTO> list(String code, Long chunkId) {
        var kb = new KnowledgeBaseLookup(bases).requireByCode(code);
        var query = new LambdaQueryWrapper<KnowledgeQuestion>().eq(KnowledgeQuestion::getKnowledgeBaseId, kb.getId())
                .orderByDesc(KnowledgeQuestion::getUpdateTime).orderByDesc(KnowledgeQuestion::getId);
        if (chunkId != null) query.eq(KnowledgeQuestion::getChunkId, chunkId);
        return questions.selectList(query).stream().map(this::dto).toList();
    }

    public KnowledgeQuestionDTO create(String code, KnowledgeQuestionRequest request) {
        if (request == null || request.getQuestion() == null || request.getQuestion().isBlank()
                || request.getQuestion().length() > 512) {
            throw new IllegalArgumentException("问题内容不能为空且不能超过512个字符");
        }
        String source = request.getSource() == null || request.getSource().isBlank() ? "MANUAL" : request.getSource();
        if (source.length() > 32) throw new IllegalArgumentException("问题来源不能超过32个字符");
        return transactions.execute(status -> {
            var kb = lock(code);
            if (request.getChunkId() != null) {
                var chunk = chunks.lockById(request.getChunkId());
                if (chunk == null || !Objects.equals(chunk.getKnowledgeBaseId(), kb.getId())) {
                    throw new IllegalArgumentException("问题片段不存在或不属于当前知识库");
                }
            }
            var question = new KnowledgeQuestion(); question.setKnowledgeBaseId(kb.getId());
            question.setChunkId(request.getChunkId()); question.setQuestion(request.getQuestion());
            question.setSource(source); question.setHitCount(0);
            if (questions.insert(question) != 1) throw new IllegalStateException("问题写入失败");
            return dto(question);
        });
    }

    public void delete(String code, Long id) {
        if (id == null || id <= 0) throw new IllegalArgumentException("问题ID无效");
        transactions.executeWithoutResult(status -> {
            var kb = lock(code);
            questions.delete(new LambdaQueryWrapper<KnowledgeQuestion>()
                    .eq(KnowledgeQuestion::getKnowledgeBaseId, kb.getId()).eq(KnowledgeQuestion::getId, id));
        });
    }

    /** Must precede chunk deletion/replacement and commit with that metadata transaction. */
    public void unlinkFileChunks(Long knowledgeBaseId, String fileId) {
        requireRetirementTransaction(knowledgeBaseId);
        if (fileId == null || fileId.isBlank()) throw new IllegalArgumentException("问题退场的文件ID不能为空");
        questions.unlinkFileChunks(knowledgeBaseId, fileId);
    }

    public void deleteAllInKnowledgeBase(Long knowledgeBaseId) {
        requireRetirementTransaction(knowledgeBaseId);
        questions.delete(new LambdaQueryWrapper<KnowledgeQuestion>().eq(KnowledgeQuestion::getKnowledgeBaseId, knowledgeBaseId));
    }

    private KnowledgeBase lock(String code) {
        var expected = new KnowledgeBaseLookup(bases).requireByCode(code);
        var current = bases.lockById(expected.getId());
        if (current == null || !Objects.equals(current.getCode(), expected.getCode())) {
            throw new IllegalStateException("原知识库已删除或重建");
        }
        return current;
    }

    private void requireRetirementTransaction(Long knowledgeBaseId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("问题引用退场必须与知识元数据处于同一事务");
        }
        if (knowledgeBaseId == null || bases.lockById(knowledgeBaseId) == null) {
            throw new IllegalStateException("问题所属知识库已失效");
        }
    }

    private KnowledgeQuestionDTO dto(KnowledgeQuestion question) {
        var result = new KnowledgeQuestionDTO(); BeanUtils.copyProperties(question, result); return result;
    }
}
