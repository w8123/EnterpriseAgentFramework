package com.enterprise.ai.control.mcp.infrastructure.persistence;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.control.mcp.application.port.McpPublishedReferenceQuery;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class McpPublishedReferenceReader implements McpPublishedReferenceQuery {
    private static final int LIMIT = 10_000;
    private final McpPublicationMapper publications;
    private final McpPublicationRevisionMapper revisions;
    private final ObjectMapper json;

    @Override
    @Transactional(readOnly = true)
    public Evidence inspect() {
        var published = publications.selectList(Wrappers.<McpPublicationEntity>lambdaQuery()
                .eq(McpPublicationEntity::getState, "PUBLISHED")
                .orderByAsc(McpPublicationEntity::getId).last("limit " + (LIMIT + 1)));
        if (published.isEmpty()) return new Evidence(true, List.of());
        boolean complete = published.size() <= LIMIT;
        var byId = new LinkedHashMap<Long, McpPublicationEntity>();
        published.stream().limit(LIMIT).forEach(value -> byId.put(value.getId(), value));
        // One bounded batch for all publications, including historical pinned revisions.
        var frozen = revisions.selectList(Wrappers.<McpPublicationRevisionEntity>lambdaQuery()
                .in(McpPublicationRevisionEntity::getPublicationId, byId.keySet())
                .orderByAsc(McpPublicationRevisionEntity::getId).last("limit " + (LIMIT + 1)));
        complete &= frozen.size() <= LIMIT;
        Set<Long> currentFound = new HashSet<>();
        List<Binding> bindings = new ArrayList<>();
        for (var revision : frozen.stream().limit(LIMIT).toList()) {
            var publication = byId.get(revision.getPublicationId());
            if (publication == null) { complete = false; continue; }
            if (revision.getId().equals(publication.getCurrentRevisionId())) currentFound.add(publication.getId());
            try {
                var tools = json.readTree(revision.getToolsSnapshotJson());
                if (tools == null || !tools.isArray()) { complete = false; continue; }
                for (var tool : tools) {
                    String kind = tool.path("sourceKind").asText("");
                    String ref = tool.path("sourceRef").asText("");
                    var version = tool.path("workflowVersionId");
                    if (ref.isBlank() || !("CAPABILITY".equals(kind) || "WORKFLOW".equals(kind)
                            && version.isIntegralNumber() && version.canConvertToLong() && version.longValue() > 0)) {
                        complete = false; continue;
                    }
                    bindings.add(new Binding(publication.getId(), publication.getName(), revision.getRevisionNo(),
                            kind, ref, "WORKFLOW".equals(kind) ? version.longValue() : null));
                }
            } catch (Exception invalid) { complete = false; }
        }
        complete &= currentFound.containsAll(byId.keySet());
        return new Evidence(complete, bindings);
    }
}
