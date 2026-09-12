package com.enterprise.ai.runtime.agent;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/** Agent-owned bindings exposed as immutable usage evidence, never persistence rows. */
public interface RuntimeAgentWorkflowUsageQuery {
    Evidence publishedBindings(Collection<Long> externallyPinnedConfigs);

    record Binding(String agentId, String agentName, Long configVersionId, String workflowId,
                   Long workflowVersionId, boolean active) { }

    record Evidence(List<Binding> bindings, Set<String> warnings) {
        public Evidence { bindings = List.copyOf(bindings); warnings = Set.copyOf(warnings); }
    }
}
