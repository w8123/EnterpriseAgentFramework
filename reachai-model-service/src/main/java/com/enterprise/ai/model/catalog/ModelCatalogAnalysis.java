package com.enterprise.ai.model.catalog;

import java.util.List;

public record ModelCatalogAnalysis(
        List<ModelCatalogCandidate> candidates,
        String rawAnalysisJson,
        String analysisStatus) {
}
