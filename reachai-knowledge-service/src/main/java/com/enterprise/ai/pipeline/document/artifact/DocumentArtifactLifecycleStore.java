package com.enterprise.ai.pipeline.document.artifact;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.ai.domain.entity.DocumentArtifactLifecycle;
import com.enterprise.ai.domain.entity.DocumentImportJob;
import com.enterprise.ai.domain.entity.FileInfo;
import com.enterprise.ai.repository.DocumentArtifactLifecycleRepository;
import com.enterprise.ai.repository.DocumentImportJobRepository;
import com.enterprise.ai.repository.FileInfoRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.UUID;

@Component
public class DocumentArtifactLifecycleStore {
    private final DocumentArtifactLifecycleRepository artifacts;
    private final DocumentImportJobRepository jobs;
    private final FileInfoRepository files;
    private final TransactionTemplate independent;
    private final TransactionTemplate metadata;

    public DocumentArtifactLifecycleStore(DocumentArtifactLifecycleRepository artifacts,
                                          DocumentImportJobRepository jobs, FileInfoRepository files,
                                          PlatformTransactionManager manager) {
        this.artifacts = artifacts;
        this.jobs = jobs;
        this.files = files;
        independent = new TransactionTemplate(manager);
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        metadata = new TransactionTemplate(manager);
    }

    public void register(String storage, String key) {
        String id = DocumentArtifactIdentity.artifact(storage, key);
        independent.executeWithoutResult(status -> {
            if (artifacts.register(id, storage, key) != 1) {
                throw new IllegalStateException("无法登记工件写入身份");
            }
        });
    }

    public void acknowledge(String storage, String key) {
        String id = DocumentArtifactIdentity.artifact(storage, key);
        independent.executeWithoutResult(status -> artifacts.acknowledge(id));
    }

    public void retain(String storage, String key) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("工件保留必须与引用发布在同一事务中提交");
        }
        String id = DocumentArtifactIdentity.artifact(storage, key);
        requireIdentity(artifacts.lock(id), storage, key);
        if (artifacts.lockPublishable(id) == null || artifacts.retain(id) != 1) {
            throw new IllegalStateException("工件写入未确认或已经失去发布资格");
        }
    }

    public void retire(String storage, String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        String id = DocumentArtifactIdentity.artifact(storage, key);
        metadata.executeWithoutResult(status -> {
            var current = artifacts.lock(id);
            if (current == null) {
                // A saved legacy key proves identity, but not completion of every historical write.
                current = new DocumentArtifactLifecycle();
                current.setArtifactId(id);
                current.setStorageId(storage);
                current.setObjectKey(key);
                current.setState("RECLAIMING");
                current.setWriteAcknowledged(false);
                if (artifacts.insert(current) != 1) {
                    throw new IllegalStateException("无法登记历史工件退场");
                }
            } else {
                requireIdentity(current, storage, key);
                artifacts.retire(id);
            }
        });
    }

    public DocumentArtifactLifecycle claim(String storage, DocumentArtifactLifecycle candidate) {
        DocumentArtifactIdentity.storage(storage);
        return independent.execute(status -> {
            var current = artifacts.lock(candidate.getArtifactId());
            if (current == null || !Objects.equals(current.getStorageId(), storage)) {
                return null;
            }
            requireIdentity(current, storage, current.getObjectKey());
            // Reference publication locks this journal row before committing. Nonlocking reads here
            // avoid reversing the job/file -> artifact lock order used by business transactions.
            String key = current.getObjectKey();
            if (files.selectCount(new LambdaQueryWrapper<FileInfo>()
                    .and(q -> q.eq(FileInfo::getSourceObjectKey, key).or().eq(FileInfo::getParseArtifactObjectKey, key))) > 0
                    || jobs.selectCount(new LambdaQueryWrapper<DocumentImportJob>()
                    .notIn(DocumentImportJob::getStatus, "COMPLETED", "CANCELLED")
                    .and(q -> q.eq(DocumentImportJob::getSourceObjectKey, key)
                            .or().eq(DocumentImportJob::getParseArtifactObjectKey, key))) > 0) {
                artifacts.deferReferenced(current.getArtifactId());
                return null;
            }
            String owner = UUID.randomUUID().toString();
            if (artifacts.claim(current.getArtifactId(), storage, owner) != 1) {
                return null;
            }
            current.setCleanupLeaseOwner(owner);
            return current;
        });
    }

    public void finish(DocumentArtifactLifecycle claimed, String error) {
        independent.executeWithoutResult(status -> artifacts.finish(claimed.getArtifactId(),
                claimed.getCleanupLeaseOwner(), error == null ? 300 : 30, error));
    }

    private void requireIdentity(DocumentArtifactLifecycle record, String storage, String key) {
        if (record == null || !Objects.equals(record.getStorageId(), storage) || !Objects.equals(record.getObjectKey(), key)
                || !Objects.equals(record.getArtifactId(), DocumentArtifactIdentity.artifact(storage, key))) {
            throw new IllegalStateException("文档工件持久化身份不一致");
        }
    }
}
