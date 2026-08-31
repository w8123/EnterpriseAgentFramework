package com.enterprise.ai.control.mcp.application.port;

import com.enterprise.ai.control.mcp.domain.publication.McpPublication;
import com.enterprise.ai.control.mcp.domain.publication.McpPublicationItem;
import com.enterprise.ai.control.mcp.domain.publication.McpPublicationRevision;

import java.util.List;
import java.util.Optional;

public interface McpPublicationRepository {

    Optional<McpPublication> findById(long id);

    Optional<McpPublication> findByName(String name);

    boolean existsByName(String name);

    Page findPage(String search, String state, int limit, int offset);

    McpPublication save(McpPublication publication);

    List<McpPublicationItem> findItems(long publicationId);

    McpPublicationItem saveItem(McpPublicationItem item);

    boolean deleteItem(long publicationId, long itemId);

    int nextRevisionNo(long publicationId);

    McpPublicationRevision saveRevision(McpPublicationRevision revision);

    Optional<McpPublicationRevision> findRevision(long publicationId, long revisionId);

    List<McpPublicationRevision> findRevisions(long publicationId);

    record Page(List<McpPublication> items, long total) {
        public Page {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }
}
