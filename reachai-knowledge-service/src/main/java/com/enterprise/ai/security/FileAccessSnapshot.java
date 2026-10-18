package com.enterprise.ai.security;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Server-created identities for one retrieval; neither business IDs nor primary keys identify a generation.
 * {@code openKnowledgeBaseIds} are enabled ALL_CALLERS knowledge bases: every trusted caller may read their
 * current enabled chunks without a per-file grant.
 */
public record FileAccessSnapshot(String userId, Map<Long, Grant> grants, Set<Long> openKnowledgeBaseIds) {
    public FileAccessSnapshot {
        if (userId == null || userId.isBlank()) throw new IllegalArgumentException("检索用户不能为空");
        userId = userId.trim();
        grants = Map.copyOf(grants);
        openKnowledgeBaseIds = openKnowledgeBaseIds == null ? Set.of() : Set.copyOf(openKnowledgeBaseIds);
    }

    public FileAccessSnapshot(String userId, Map<Long, Grant> grants) {
        this(userId, grants, Set.of());
    }

    public boolean hasAccess() {
        return !grants.isEmpty() || !openKnowledgeBaseIds.isEmpty();
    }

    public boolean isOpen(Long knowledgeBaseId) {
        return knowledgeBaseId != null && openKnowledgeBaseIds.contains(knowledgeBaseId);
    }

    public List<String> fileIds() {
        return grants.values().stream().map(Grant::fileId).distinct().sorted().toList();
    }

    public boolean includes(AuthorizedKnowledgeChunk chunk) {
        if (chunk.grantId() == null) return isOpen(chunk.knowledgeBaseId());
        Grant grant = grants.get(chunk.grantId());
        return grant != null && grant.hasRecordGenerations()
                && Objects.equals(grant.grantGeneration(), chunk.grantGeneration())
                && Objects.equals(grant.fileGeneration(), chunk.fileGeneration())
                && Objects.equals(grant.fileRecordId(), chunk.fileRecordId())
                && Objects.equals(grant.knowledgeBaseId(), chunk.knowledgeBaseId())
                && Objects.equals(grant.fileId(), chunk.fileId())
                && Objects.equals(grant.collectionName(), chunk.collectionName());
    }

    public record Grant(Long grantId, String grantGeneration, Long fileRecordId, String fileGeneration,
                        Long knowledgeBaseId, String fileId, String collectionName) {
        public boolean hasRecordGenerations() {
            return grantGeneration != null && grantGeneration.matches("[0-9a-f]{32}")
                    && fileGeneration != null && fileGeneration.matches("[0-9a-f]{32}");
        }
    }
}
