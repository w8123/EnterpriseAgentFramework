package com.enterprise.ai.external;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Dify knowledge API with a dataset API key: {@code GET /v1/datasets} and {@code POST /v1/datasets/{id}/retrieve}.
 * The address is the Dify API root shown on the knowledge "API" page, e.g. {@code https://api.dify.ai/v1}.
 * Retrieval uses each dataset's own retrieval settings in Dify.
 */
@Component
public class DifyKnowledgeProvider implements ExternalKnowledgeProvider {
    static final String TYPE = "DIFY";
    private final ExternalKnowledgeHttp http;

    public DifyKnowledgeProvider(ExternalKnowledgeHttp http) {
        this.http = http;
    }

    @Override public String type() { return TYPE; }
    @Override public String displayName() { return "Dify 知识库"; }

    @Override
    public List<ExternalKnowledgeConnection.RemoteDataset> listDatasets(ExternalKnowledgeConnection connection) {
        List<ExternalKnowledgeConnection.RemoteDataset> datasets = new ArrayList<>();
        for (int page = 1; page <= 10; page++) {
            JsonNode response = http.get(apiUrl(connection, "/datasets?page=" + page + "&limit=100"), headers(connection));
            for (JsonNode item : response.path("data")) {
                String id = item.path("id").asText("");
                if (!id.isBlank()) datasets.add(new ExternalKnowledgeConnection.RemoteDataset(id, item.path("name").asText(id)));
            }
            if (!response.path("has_more").asBoolean(false)) break;
        }
        return datasets;
    }

    @Override
    public List<ExternalKnowledgeHit> search(ExternalKnowledgeConnection connection, String query, int topK, float threshold) {
        List<ExternalKnowledgeHit> hits = new ArrayList<>();
        for (String datasetId : connection.datasetIds()) {
            JsonNode response = http.post(apiUrl(connection, "/datasets/" + encode(datasetId) + "/retrieve"),
                    Map.of("query", query), headers(connection));
            int taken = 0;
            for (JsonNode record : response.path("records")) {
                JsonNode segment = record.path("segment");
                String content = segment.path("content").asText("");
                if (content.isBlank()) continue;
                float score = (float) Math.max(0d, Math.min(1d, record.path("score").asDouble(0d)));
                if (score < threshold) continue;
                JsonNode document = segment.path("document");
                hits.add(new ExternalKnowledgeHit(segment.path("id").asText(""), document.path("id").asText(""),
                        document.path("name").asText(""), content, score));
                if (++taken >= Math.max(1, topK)) break;
            }
        }
        return hits;
    }

    static String apiUrl(ExternalKnowledgeConnection connection, String path) {
        String base = connection.baseUrl() == null ? "" : connection.baseUrl().trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        if (!base.endsWith("/v1")) base = base + "/v1";
        return ExternalKnowledgeHttp.join(base, path);
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static Map<String, String> headers(ExternalKnowledgeConnection connection) {
        return Map.of("Authorization", "Bearer " + connection.apiKey());
    }
}
