package com.enterprise.ai.capability.catalog.graph;

public record CapabilityApiGraphParamSourceHintView(
        String targetPath,
        String targetField,
        String targetApi,
        String sourcePath,
        String sourceField,
        String sourceApi,
        Double confidence) {
}
