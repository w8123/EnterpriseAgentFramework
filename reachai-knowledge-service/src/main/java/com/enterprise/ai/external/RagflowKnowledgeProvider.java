package com.enterprise.ai.external;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RAGFlow HTTP API (v0.13+): {@code GET /api/v1/datasets} and {@code POST /api/v1/retrieval}, Bearer API key.
 * The address is the RAGFlow web/API root, e.g. {@code http://10.0.0.5:9380} or {@code https://ragflow.example.com}.
 */
@Component
public class RagflowKnowledgeProvider implements ExternalKnowledgeProvider {
    static final String TYPE = "RAGFLOW";
    private final ExternalKnowledgeHttp http;

    public RagflowKnowledgeProvider(ExternalKnowledgeHttp http) {
        this.http = http;
    }

    @Override public String type() { return TYPE; }
    @Override public String displayName() { return "RAGFlow"; }

    @Override
    public List<ExternalKnowledgeConnection.RemoteDataset> listDatasets(ExternalKnowledgeConnection connection) {
        List<ExternalKnowledgeConnection.RemoteDataset> datasets = new ArrayList<>();
        for (int page = 1; page <= 10; page++) {
            JsonNode data = data(http.get(apiUrl(connection, "/datasets?page=" + page + "&page_size=100&orderby=update_time&desc=true"),
                    headers(connection)));
            if (!data.isArray() || data.isEmpty()) break;
            for (JsonNode item : data) {
                String id = item.path("id").asText("");
                if (!id.isBlank()) datasets.add(new ExternalKnowledgeConnection.RemoteDataset(id, item.path("name").asText(id)));
            }
            if (data.size() < 100) break;
        }
        return datasets;
    }

    @Override
    public List<ExternalKnowledgeHit> search(ExternalKnowledgeConnection connection, String query, int topK, float threshold) {
        if (connection.datasetIds().isEmpty()) return List.of();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("question", query);
        body.put("dataset_ids", connection.datasetIds());
        body.put("page", 1);
        body.put("page_size", Math.max(1, topK));
        body.put("similarity_threshold", Math.max(0f, Math.min(1f, threshold)));
        body.put("top_k", 1024);
        JsonNode data = data(http.post(apiUrl(connection, "/retrieval"), body, headers(connection)));
        List<ExternalKnowledgeHit> hits = new ArrayList<>();
        for (JsonNode chunk : data.path("chunks")) {
            String content = chunk.path("content").asText("");
            if (content.isBlank()) continue;
            float score = (float) chunk.path("similarity").asDouble(0d);
            hits.add(new ExternalKnowledgeHit(chunk.path("id").asText(""), chunk.path("document_id").asText(""),
                    chunk.path("document_keyword").asText(chunk.path("docnm_kwd").asText("")), content,
                    Math.max(0f, Math.min(1f, score))));
        }
        return hits;
    }

    static String apiUrl(ExternalKnowledgeConnection connection, String path) {
        String base = connection.baseUrl() == null ? "" : connection.baseUrl().trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        if (base.endsWith("/api/v1")) base = base.substring(0, base.length() - "/api/v1".length());
        return ExternalKnowledgeHttp.join(base, "/api/v1" + path);
    }

    private static Map<String, String> headers(ExternalKnowledgeConnection connection) {
        return Map.of("Authorization", "Bearer " + connection.apiKey());
    }

    /** RAGFlow wraps every answer as {code, message, data}; code 0 is success even with HTTP 200 errors. */
    private static JsonNode data(JsonNode response) {
        int code = response.path("code").asInt(0);
        if (code != 0) {
            String message = response.path("message").asText("");
            if (code == 109 || message.toLowerCase(java.util.Locale.ROOT).contains("authentication")) {
                throw new ExternalKnowledgeException("RAGFlow 拒绝了 API Key，请检查密钥");
            }
            throw new ExternalKnowledgeException("RAGFlow 返回错误（" + code + "）" + (message.isBlank() ? "" : "：" + message));
        }
        return response.path("data");
    }
}
