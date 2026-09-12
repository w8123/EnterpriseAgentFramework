package com.enterprise.ai.runtime.api;

public record RuntimeWorkflowVersionPublishRequest(
        String version,
        Integer rolloutPercent,
        String note,
        String baseRevision) {
}
