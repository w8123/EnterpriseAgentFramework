package com.enterprise.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.ai.domain.entity.Chunk;
import com.enterprise.ai.domain.entity.KnowledgeBase;
import com.enterprise.ai.domain.entity.KnowledgeCollectionLifecycle;
import com.enterprise.ai.repository.ChunkRepository;
import com.enterprise.ai.repository.KnowledgeBaseRepository;
import com.enterprise.ai.repository.KnowledgeCollectionLifecycleRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.UUID;

@Component
public class KnowledgeCollectionLifecycleStore {
    private final KnowledgeCollectionLifecycleRepository collections;
    private final KnowledgeBaseRepository bases;
    private final ChunkRepository chunks;
    private final TransactionTemplate independent;

    public KnowledgeCollectionLifecycleStore(KnowledgeCollectionLifecycleRepository collections,
                                             KnowledgeBaseRepository bases,
                                             ChunkRepository chunks,
                                             PlatformTransactionManager manager) {
        this.collections = collections;
        this.bases = bases;
        this.chunks = chunks;
        independent = new TransactionTemplate(manager);
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void register(KnowledgeBase base) {
        if (base.getId() != null || base.getVectorCollectionName() == null
                || !base.getVectorCollectionName().matches("reachai_kb_[a-f0-9]{32}")
                || base.getDimension() == null || base.getDimension() < 1) {
            throw new IllegalArgumentException("无效的新知识库物理身份或维度");
        }
        independent.executeWithoutResult(status -> {
            if (collections.register(base.getVectorCollectionName(), base.getCode(), base.getDimension()) != 1) {
                throw new IllegalStateException("无法登记集合创建意图");
            }
        });
    }

    public void acknowledge(String collection) {
        independent.executeWithoutResult(status -> collections.acknowledge(collection));
    }

    public void publish(KnowledgeBase base) {
        independent.executeWithoutResult(status -> {
            var record = collections.lock(base.getVectorCollectionName());
            if (record == null || !Objects.equals(record.getKnowledgeBaseCode(), base.getCode())
                    || !Objects.equals(record.getDimension(), base.getDimension())
                    || collections.lockPublishable(base.getVectorCollectionName()) == null) {
                throw new IllegalStateException("集合创建已失去发布资格，请重新创建知识库");
            }
            if (bases.insert(base) != 1 || collections.publish(base.getVectorCollectionName(), base.getId()) != 1) {
                throw new IllegalStateException("无法原子发布知识库与物理集合");
            }
        });
    }

    public void abandon(String collection) {
        independent.executeWithoutResult(status -> collections.abandon(collection));
    }

    /** The caller holds the owning knowledge-base row until its deletion and this intent commit together. */
    public void retire(KnowledgeBase base) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("集合退场必须与知识库删除一起提交");
        }
        var record = collections.lock(base.getVectorCollectionName());
        if (record == null) {
            // Legacy mappings have no durable evidence that every old create request has finished.
            record = new KnowledgeCollectionLifecycle();
            record.setCollectionName(base.getVectorCollectionName());
            record.setKnowledgeBaseId(base.getId());
            record.setKnowledgeBaseCode(base.getCode());
            record.setDimension(base.getDimension());
            record.setState("RECLAIMING");
            record.setCreateAcknowledged(false);
            if (collections.insert(record) != 1) {
                throw new IllegalStateException("无法登记历史集合退场");
            }
        } else {
            if (!Objects.equals(record.getKnowledgeBaseId(), base.getId())
                    || !Objects.equals(record.getKnowledgeBaseCode(), base.getCode())
                    || !"READY".equals(record.getState())) {
                throw new IllegalStateException("知识库与集合生命周期归属不一致");
            }
            if (collections.retire(base.getVectorCollectionName()) != 1) {
                throw new IllegalStateException("无法保存集合退场意图");
            }
        }
    }

    public KnowledgeCollectionLifecycle claim(KnowledgeCollectionLifecycle candidate) {
        return independent.execute(status -> {
            var current = collections.lock(candidate.getCollectionName());
            if (current == null) {
                return null;
            }
            // Publication owns this journal lock before inserting the base. Do not acquire a base gap
            // lock in the reverse order; a concurrent creator could otherwise deadlock on its insert.
            if (bases.selectCount(new LambdaQueryWrapper<KnowledgeBase>()
                    .eq(KnowledgeBase::getVectorCollectionName, current.getCollectionName())) > 0
                    || chunks.selectCount(new LambdaQueryWrapper<Chunk>()
                    .eq(Chunk::getCollectionName, current.getCollectionName())) > 0) {
                collections.deferReferenced(current.getCollectionName());
                return null;
            }
            String lease = UUID.randomUUID().toString();
            if (collections.claim(current.getCollectionName(), lease) != 1) {
                return null;
            }
            current.setCleanupLeaseOwner(lease);
            return current;
        });
    }

    public void finish(KnowledgeCollectionLifecycle claimed, String error) {
        independent.executeWithoutResult(status -> collections.finish(claimed.getCollectionName(),
                claimed.getCleanupLeaseOwner(), error == null ? 300 : 30, error));
    }
}
