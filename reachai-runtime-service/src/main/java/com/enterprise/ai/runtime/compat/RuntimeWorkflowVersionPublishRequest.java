package com.enterprise.ai.runtime.compat;

public record RuntimeWorkflowVersionPublishRequest(
        String version,
        Integer rolloutPercent,
        String note,
        String publishedBy,
        String baseRevision) {

    public RuntimeWorkflowVersionPublishRequest(String version,
                                                Integer rolloutPercent,
                                                String note,
                                                String publishedBy) {
        this(version, rolloutPercent, note, publishedBy, null);
    }
}
