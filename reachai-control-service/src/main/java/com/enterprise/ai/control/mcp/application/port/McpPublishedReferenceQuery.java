package com.enterprise.ai.control.mcp.application.port;

import java.util.List;

/** Frozen references in revisions still reachable through published MCP publications. */
public interface McpPublishedReferenceQuery {
    Evidence inspect();

    /** Keeps global completeness while returning only the requested capability and exact Workflow-version identities. */
    default Evidence inspect(java.util.Set<String> capabilityAliases, java.util.Map<Long, String> workflowVersions) {
        var evidence = inspect();
        return new Evidence(evidence.complete(), evidence.bindings().stream().filter(binding ->
                "CAPABILITY".equals(binding.sourceKind()) ? capabilityAliases.contains(binding.sourceRef())
                        : "WORKFLOW".equals(binding.sourceKind())
                        && java.util.Objects.equals(workflowVersions.get(binding.workflowVersionId()), binding.sourceRef())).toList());
    }

    record Binding(Long publicationId, String publicationName, Integer revisionNo,
                   String sourceKind, String sourceRef, Long workflowVersionId) { }

    record Evidence(boolean complete, List<Binding> bindings) {
        public Evidence { bindings = List.copyOf(bindings); }
    }
}
