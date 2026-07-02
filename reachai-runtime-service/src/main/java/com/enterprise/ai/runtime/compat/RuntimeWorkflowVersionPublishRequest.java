package com.enterprise.ai.runtime.compat;

public record RuntimeWorkflowVersionPublishRequest(
        String version,
        Integer rolloutPercent,
        String note,
        String publishedBy) {
}
