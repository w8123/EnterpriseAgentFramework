package com.enterprise.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.ai.domain.dto.DocumentImportAccessContext;
import com.enterprise.ai.domain.entity.DocumentImportJob;
import com.enterprise.ai.domain.entity.FileInfo;
import com.enterprise.ai.domain.entity.KnowledgeBase;
import com.enterprise.ai.domain.entity.UserFilePermission;
import com.enterprise.ai.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.Set;

/** Only the attested importer can obtain a read grant for their current published file. */
@Service
public class KnowledgeFileReadAccessService {
    private final KnowledgeBaseRepository bases;
    private final FileInfoRepository files;
    private final DocumentImportJobRepository jobs;
    private final UserFilePermissionRepository permissions;
    private final TransactionTemplate transactions;

    public KnowledgeFileReadAccessService(KnowledgeBaseRepository bases, FileInfoRepository files,
            DocumentImportJobRepository jobs, UserFilePermissionRepository permissions,
            PlatformTransactionManager manager) {
        this.bases = bases; this.files = files; this.jobs = jobs; this.permissions = permissions;
        transactions = new TransactionTemplate(manager);
    }

    public ReadAccess readOwn(String fileId, DocumentImportAccessContext identity) {
        requireIdentity(identity);
        return transactions.execute(status -> {
            FileInfo file = requireFile(fileId);
            DocumentImportJob job = requireJob(file.getImportJobId(), false);
            verify(file, bases.selectById(file.getKnowledgeBaseId()), job, identity, "COMPLETED", false);
            return access(file, identity.actorId().trim());
        });
    }

    public ReadAccess ensureOwn(String fileId, DocumentImportAccessContext assertion) {
        requireIdentity(assertion);
        return transactions.execute(status -> {
            FileInfo observed = requireFile(fileId);
            KnowledgeBase base = bases.lockById(observed.getKnowledgeBaseId());
            DocumentImportJob job = requireJob(observed.getImportJobId(), true);
            FileInfo file = files.lockByFileId(fileId);
            if (file == null || !file.hasRecordIdentity(observed.getId(), observed.requireRecordGeneration())) {
                throw new IllegalStateException("原文件已删除或重建，请刷新后重试");
            }
            verify(file, base, job, assertion, "COMPLETED", true);
            ensure(file, assertion.actorId().trim());
            return access(file, assertion.actorId().trim());
        });
    }

    /** Runs while the existing metadata transaction holds the base and import-job locks. */
    public void grantPublishedImporter(DocumentImportJob job) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("文件授权必须与索引发布处于同一事务");
        }
        if (job == null) throw new IllegalArgumentException("导入任务不能为空");
        var identity = new DocumentImportAccessContext(job.getTenantId(), job.getCreatedByActorId(),
                job.getWorkspaceId(), job.getProjectCode(), job.getResourceScope());
        requireIdentity(identity);
        FileInfo file = files.lockByFileId(job.getFileId());
        verify(file, bases.selectById(job.getKnowledgeBaseId()), job, identity, "INDEXING", true);
        ensure(file, identity.actorId().trim());
    }

    private void ensure(FileInfo file, String actor) {
        UserFilePermission existing = grant(file.getFileId(), actor);
        if (existing != null) {
            if (!readable(existing)) throw new IllegalStateException("已有文件授权无有效读取身份，请先处理该授权记录");
            return; // A repeated call never replaces a grant generation or escalates its type.
        }
        UserFilePermission grant = new UserFilePermission();
        grant.setUserId(actor); grant.setFileId(file.getFileId()); grant.setPermissionType("read");
        if (permissions.insert(grant) != 1) throw new IllegalStateException("无法保存文件读取授权");
    }

    private ReadAccess access(FileInfo file, String actor) {
        UserFilePermission grant = grant(file.getFileId(), actor);
        return new ReadAccess(file.getFileId(), actor, grant == null ? null : grant.getPermissionType(), readable(grant));
    }

    private UserFilePermission grant(String fileId, String actor) {
        return permissions.selectOne(new LambdaQueryWrapper<UserFilePermission>()
                .eq(UserFilePermission::getFileId, fileId).eq(UserFilePermission::getUserId, actor));
    }

    private static boolean readable(UserFilePermission grant) {
        return grant != null && grant.getPermissionType() != null && Set.of("read", "write", "admin").contains(grant.getPermissionType())
                && grant.getRecordGeneration() != null && grant.getRecordGeneration().matches("[0-9a-f]{32}");
    }

    private FileInfo requireFile(String fileId) {
        if (fileId == null || !fileId.matches("[A-Za-z0-9._-]{1,128}")) throw new IllegalArgumentException("文件ID无效");
        FileInfo file = files.selectOne(new LambdaQueryWrapper<FileInfo>().eq(FileInfo::getFileId, fileId));
        if (file == null) throw new IllegalArgumentException("文件不存在");
        file.requireRecordGeneration();
        return file;
    }

    private DocumentImportJob requireJob(String jobId, boolean lock) {
        if (jobId == null || jobId.isBlank()) throw new IllegalArgumentException("文件没有可信导入任务");
        var query = new LambdaQueryWrapper<DocumentImportJob>().eq(DocumentImportJob::getJobId, jobId);
        if (lock) query.last("FOR UPDATE");
        DocumentImportJob job = jobs.selectOne(query);
        if (job == null) throw new IllegalArgumentException("文件的导入任务不存在");
        return job;
    }

    private static void verify(FileInfo file, KnowledgeBase base, DocumentImportJob job,
            DocumentImportAccessContext identity, String expectedState, boolean assertScope) {
        if (file == null || base == null || !Objects.equals(base.getStatus(), 1)
                || !Objects.equals(file.getStatus(), 1) || !expectedState.equals(job.getStatus())
                || !Objects.equals(job.getTenantId(), identity.tenantId().trim())
                || !Objects.equals(job.getCreatedByActorId(), identity.actorId().trim())
                || !Objects.equals(file.getImportJobId(), job.getJobId())
                || !Objects.equals(file.getFileId(), job.getFileId())
                || !Objects.equals(file.getKnowledgeBaseId(), base.getId())
                || !Objects.equals(job.getKnowledgeBaseId(), base.getId())
                || !Objects.equals(job.getKnowledgeBaseCode(), base.getCode())
                || base.getVectorCollectionName() == null || base.getVectorCollectionName().isBlank()
                || !Objects.equals(job.getVectorCollectionName(), base.getVectorCollectionName())
                || !Objects.equals(job.getWorkspaceId(), workspace(base.getWorkspaceId()))
                || !Objects.equals(job.getProjectCode(), project(base.getProjectCode()))
                || !Objects.equals(job.getResourceScope(), scope(base.getScope()))) {
            throw new IllegalArgumentException("只能授权当前身份自己导入并发布且作用域未变化的文件");
        }
        file.requireRecordGeneration();
        if (assertScope && (!Objects.equals(identity.workspaceId(), job.getWorkspaceId())
                || !Objects.equals(project(identity.projectCode()), job.getProjectCode())
                || !Objects.equals(identity.scope(), job.getResourceScope()))) {
            throw new IllegalArgumentException("文件授权作用域不匹配，请刷新后重试");
        }
    }

    private static void requireIdentity(DocumentImportAccessContext identity) {
        if (identity == null || invalid(identity.tenantId(), 96) || invalid(identity.actorId(), 128)) {
            throw new IllegalArgumentException("文件授权需要已验证的 Control 身份");
        }
    }
    private static boolean invalid(String value, int max) {
        return value == null || value.isBlank() || value.length() > max || value.chars().anyMatch(Character::isISOControl);
    }
    private static String workspace(String value) { return value == null || value.isBlank() ? "default" : value.trim(); }
    private static String project(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static String scope(String value) {
        String scope = value == null || value.isBlank() ? "WORKSPACE" : value.trim().toUpperCase(java.util.Locale.ROOT);
        if (!Set.of("PROJECT", "WORKSPACE", "SHARED").contains(scope)) throw new IllegalArgumentException("知识库作用域无效");
        return scope;
    }
    public record ReadAccess(String fileId, String userId, String permissionType, boolean readable) { }
}
