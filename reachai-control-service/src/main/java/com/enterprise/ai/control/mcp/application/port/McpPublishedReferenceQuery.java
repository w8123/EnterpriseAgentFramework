package com.enterprise.ai.control.mcp.application.port;

import java.util.List;

/** Frozen references in revisions still reachable through published MCP publications. */
public interface McpPublishedReferenceQuery {
    Evidence inspect();

    record Binding(Long publicationId, String publicationName, Integer revisionNo,
                   String sourceKind, String sourceRef, Long workflowVersionId) { }

    record Evidence(boolean complete, List<Binding> bindings) {
        public Evidence { bindings = List.copyOf(bindings); }
    }
}
