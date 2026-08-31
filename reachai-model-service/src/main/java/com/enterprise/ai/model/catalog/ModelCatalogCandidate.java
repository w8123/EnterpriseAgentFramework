package com.enterprise.ai.model.catalog;

import java.util.Map;

public record ModelCatalogCandidate(
        String modelName,
        String displayName,
        String modelType,
        String lifecycleStatus,
        String releasedAt,
        String deprecatedAt,
        String retireAt,
        String replacementModelName,
        String officialPositioning,
        Map<String, Object> capabilities,
        String evidenceExcerpt,
        double confidence) {
}
