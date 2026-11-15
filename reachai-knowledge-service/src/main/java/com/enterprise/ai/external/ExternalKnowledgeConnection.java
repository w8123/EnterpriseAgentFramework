package com.enterprise.ai.external;

import java.util.List;

/**
 * One saved (or draft) connection to an external knowledge base system.
 * {@code apiKey} is plaintext only in memory; it is stored encrypted and never returned to the console.
 */
public record ExternalKnowledgeConnection(String provider, String baseUrl, String clientId, String apiKey,
                                          List<RemoteDataset> datasets) {
    public ExternalKnowledgeConnection {
        datasets = datasets == null ? List.of() : List.copyOf(datasets);
    }

    public List<String> datasetIds() {
        return datasets.stream().map(RemoteDataset::id).toList();
    }

    public ExternalKnowledgeConnection withApiKey(String key) {
        return new ExternalKnowledgeConnection(provider, baseUrl, clientId, key, datasets);
    }

    /** A dataset (RAGFlow) / knowledge base (ima) / dataset (Dify) on the remote side. */
    public record RemoteDataset(String id, String name) { }
}
