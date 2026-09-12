package com.enterprise.ai.security.impl;

import com.enterprise.ai.repository.UserFilePermissionRepository;
import com.enterprise.ai.security.PermissionService;
import com.enterprise.ai.security.FileAccessSnapshot;
import com.enterprise.ai.security.AuthorizedKnowledgeChunk;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Service
public class PermissionServiceImpl implements PermissionService {

    private final UserFilePermissionRepository permissionRepository;
    private final TransactionTemplate currentReads;

    public PermissionServiceImpl(UserFilePermissionRepository permissionRepository, PlatformTransactionManager manager) {
        this.permissionRepository = permissionRepository;
        currentReads = new TransactionTemplate(manager);
        currentReads.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        currentReads.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        currentReads.setReadOnly(true);
    }

    @Override
    public FileAccessSnapshot capture(String userId) {
        if (userId == null || userId.isBlank()) throw new IllegalArgumentException("检索用户不能为空");
        String actor = userId.trim();
        return currentReads.execute(status -> new FileAccessSnapshot(actor,
                permissionRepository.selectFileGrantsByUserId(actor).stream()
                        .filter(FileAccessSnapshot.Grant::hasRecordGenerations)
                        .collect(Collectors.toMap(FileAccessSnapshot.Grant::grantId, grant -> grant))));
    }

    @Override
    public List<AuthorizedKnowledgeChunk> resolveAuthorizedChunks(FileAccessSnapshot snapshot, List<Long> chunkIds) {
        Objects.requireNonNull(snapshot, "File permission snapshot is required");
        if (snapshot.grants().isEmpty() || chunkIds == null || chunkIds.isEmpty()) return List.of();
        List<Long> ids = chunkIds.stream().filter(Objects::nonNull).filter(id -> id > 0).distinct().toList();
        return currentReads.execute(status -> {
            List<AuthorizedKnowledgeChunk> authorized = new ArrayList<>();
            for (int start = 0; start < ids.size(); start += 500) {
                permissionRepository.selectAuthorizedChunks(snapshot.userId(), ids.subList(start, Math.min(start + 500, ids.size())))
                        .stream().filter(snapshot::includes).forEach(authorized::add);
            }
            return List.copyOf(authorized);
        });
    }

    @Override
    public List<String> getAccessibleFileIds(String userId) {
        // File identifiers can be reused after deletion; an old cache entry must never restore a revoked grant.
        List<String> fileIds = permissionRepository.selectFileIdsByUserId(userId);
        log.debug("查询用户权限 userId={}, fileCount={}", userId, fileIds == null ? 0 : fileIds.size());
        return fileIds == null ? List.of() : List.copyOf(fileIds);
    }

    @Override
    public String buildMilvusFilter(List<String> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) {
            return "file_id in [\"\"]";
        }
        String ids = fileIds.stream()
                .map(id -> "\"" + new String(com.fasterxml.jackson.core.io.JsonStringEncoder.getInstance().quoteAsString(id)) + "\"")
                .collect(Collectors.joining(", "));
        return "file_id in [" + ids + "]";
    }
}
