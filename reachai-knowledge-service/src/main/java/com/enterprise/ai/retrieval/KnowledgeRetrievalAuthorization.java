package com.enterprise.ai.retrieval;

import com.enterprise.ai.domain.dto.RetrievalTestResponse;
import com.enterprise.ai.security.AuthorizedKnowledgeChunk;
import com.enterprise.ai.security.FileAccessSnapshot;
import com.enterprise.ai.security.PermissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** One content-release check for Runtime, RAG and duplicate search. */
@Service
@RequiredArgsConstructor
public class KnowledgeRetrievalAuthorization {
    private final PermissionService permissions;

    public FileAccessSnapshot capture(String userId) { return permissions.capture(userId); }

    public String filter(FileAccessSnapshot snapshot) { return permissions.buildMilvusFilter(snapshot.fileIds()); }

    public List<RetrievalTestResponse.RetrievalItem> retain(FileAccessSnapshot snapshot, List<RetrievalTestResponse.RetrievalItem> items) {
        if (snapshot == null) return items; // The separately authorized admin inspection entry has no end-user snapshot.
        var current = resolve(snapshot, items.stream().filter(Objects::nonNull).map(RetrievalTestResponse.RetrievalItem::getChunkDbId).toList());
        return items.stream().filter(Objects::nonNull).filter(item -> {
            var chunk = current.get(item.getChunkDbId());
            if (chunk == null || !chunk.matches(item.getChunkDbId(), item.getChunkId(), item.getFileId(), item.getKnowledgeBaseCode(), item.getContent())) return false;
            item.setFileName(chunk.fileName());
            return true;
        }).collect(Collectors.toCollection(java.util.ArrayList::new));
    }

    public boolean isCurrent(FileAccessSnapshot snapshot, List<KnowledgeRetrievalCoreResponse.RetrievalItem> items) {
        if (snapshot == null || items == null) return false;
        var current = resolve(snapshot, items.stream().filter(Objects::nonNull).map(KnowledgeRetrievalCoreResponse.RetrievalItem::getChunkDbId).toList());
        return items.stream().allMatch(item -> {
            if (item == null) return false;
            var chunk = current.get(item.getChunkDbId());
            return chunk != null && chunk.matches(item.getChunkDbId(), item.getChunkId(), item.getFileId(), item.getKnowledgeBaseCode(), item.getContent());
        });
    }

    private Map<Long, AuthorizedKnowledgeChunk> resolve(FileAccessSnapshot snapshot, List<Long> ids) {
        return permissions.resolveAuthorizedChunks(snapshot, ids).stream()
                .collect(Collectors.toMap(AuthorizedKnowledgeChunk::chunkId, chunk -> chunk));
    }
}
