package com.enterprise.ai.runtime.workflow.draft;

import java.util.Map;

public record RuntimeWorkflowDraftResourceView(
        String kind,
        String name,
        String qualifiedName,
        Long definitionId,
        String projectCode,
        String description,
        Map<String, Object> metadata) {

    public String getKind() {
        return kind;
    }

    public String getName() {
        return name;
    }

    public String getQualifiedName() {
        return qualifiedName;
    }

    public Long getDefinitionId() {
        return definitionId;
    }

    public String getProjectCode() {
        return projectCode;
    }

    public String getDescription() {
        return description;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }
}
