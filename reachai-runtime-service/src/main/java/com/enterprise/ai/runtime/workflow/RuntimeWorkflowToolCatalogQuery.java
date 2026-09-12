package com.enterprise.ai.runtime.workflow;

import java.util.List;
import java.util.Map;

/** Workflow-owned metadata and exact published contracts for authoring Agent tool catalogs. */
public interface RuntimeWorkflowToolCatalogQuery {
    Map<String, Entry> current(List<String> workflowIds);

    Map<Reference, Entry> pinned(List<Reference> references);

    record Reference(String workflowId, Long versionId) { }

    record Workflow(String id, String keySlug, String name, String description, boolean active) { }

    record Version(Long id, String version, boolean executable,
                   String inputSchemaJson, String outputSchemaJson) { }

    record Entry(Workflow workflow, Version version) {
        public boolean activeWorkflow() {
            return workflow != null && workflow.active();
        }

        public boolean activePublishedWorkflow() {
            return activeWorkflow() && version != null;
        }
    }
}
