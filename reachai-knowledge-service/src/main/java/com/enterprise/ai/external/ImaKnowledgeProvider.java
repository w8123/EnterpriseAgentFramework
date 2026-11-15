package com.enterprise.ai.external;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tencent ima OpenAPI (credentials from ima.qq.com/agent-interface): {@code POST /openapi/wiki/v1/*} with the
 * {@code ima-openapi-clientid} / {@code ima-openapi-apikey} headers. {@code search_knowledge} returns titles and
 * highlighted snippets without a similarity score, so hits are scored by their position in ima's own ranking.
 */
@Component
public class ImaKnowledgeProvider implements ExternalKnowledgeProvider {
    static final String TYPE = "IMA";
    static final String DEFAULT_BASE_URL = "https://ima.qq.com";
    private final ExternalKnowledgeHttp http;

    public ImaKnowledgeProvider(ExternalKnowledgeHttp http) {
        this.http = http;
    }

    @Override public String type() { return TYPE; }
    @Override public String displayName() { return "腾讯 ima"; }
    @Override public String defaultBaseUrl() { return DEFAULT_BASE_URL; }
    @Override public boolean requiresClientId() { return true; }

    @Override
    public List<ExternalKnowledgeConnection.RemoteDataset> listDatasets(ExternalKnowledgeConnection connection) {
        Map<String, ExternalKnowledgeConnection.RemoteDataset> found = new LinkedHashMap<>();
        // An empty query lists every knowledge base the account can see (limit 1–20 per page).
        String cursor = "";
        for (int page = 0; page < 10; page++) {
            JsonNode data = data(call(connection, "search_knowledge_base", Map.of("query", "", "cursor", cursor, "limit", 20)));
            collect(data.path("info_list"), found);
            cursor = data.path("next_cursor").asText("");
            if (data.path("is_end").asBoolean(true) || cursor.isBlank()) break;
        }
        // Knowledge bases the account can add to are readable as well; older accounts only list them here.
        cursor = "";
        for (int page = 0; page < 5; page++) {
            JsonNode data = data(call(connection, "get_addable_knowledge_base_list", Map.of("cursor", cursor, "limit", 50)));
            collect(data.path("addable_knowledge_base_list"), found);
            cursor = data.path("next_cursor").asText("");
            if (data.path("is_end").asBoolean(true) || cursor.isBlank()) break;
        }
        return new ArrayList<>(found.values());
    }

    @Override
    public List<ExternalKnowledgeHit> search(ExternalKnowledgeConnection connection, String query, int topK, float threshold) {
        List<ExternalKnowledgeHit> hits = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String knowledgeBaseId : connection.datasetIds()) {
            JsonNode data = data(call(connection, "search_knowledge",
                    Map.of("query", query, "cursor", "", "knowledge_base_id", knowledgeBaseId)));
            int rank = 0;
            for (JsonNode item : data.path("info_list")) {
                String mediaId = item.path("media_id").asText("");
                String title = item.path("title").asText("");
                String snippet = stripMarkup(item.path("highlight_content").asText(""));
                // Folders match by name only and carry no content.
                if (mediaId.isBlank() || snippet.isBlank() && title.isBlank() || !seen.add(knowledgeBaseId + "/" + mediaId)) continue;
                String content = snippet.isBlank() ? title : (title.isBlank() ? snippet : "《" + title + "》" + snippet);
                float score = Math.max(0.5f, 0.95f - rank * 0.05f);
                hits.add(new ExternalKnowledgeHit(mediaId, mediaId, title.isBlank() ? mediaId : title, content, score));
                rank++;
                if (rank >= Math.max(1, topK)) break;
            }
        }
        return hits;
    }

    private JsonNode call(ExternalKnowledgeConnection connection, String api, Map<String, Object> body) {
        String base = connection.baseUrl() == null || connection.baseUrl().isBlank() ? DEFAULT_BASE_URL : connection.baseUrl();
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("ima-openapi-clientid", connection.clientId() == null ? "" : connection.clientId());
        headers.put("ima-openapi-apikey", connection.apiKey() == null ? "" : connection.apiKey());
        return http.post(ExternalKnowledgeHttp.join(base, "/openapi/wiki/v1/" + api), body, headers);
    }

    private static void collect(JsonNode list, Map<String, ExternalKnowledgeConnection.RemoteDataset> found) {
        for (JsonNode item : list) {
            String id = item.path("id").asText("");
            if (!id.isBlank()) found.putIfAbsent(id, new ExternalKnowledgeConnection.RemoteDataset(id, item.path("name").asText(id)));
        }
    }

    /** ima answers {code, msg, data}; some deployments use {retcode, errmsg, data}. */
    private static JsonNode data(JsonNode response) {
        JsonNode codeNode = response.has("code") ? response.path("code") : response.path("retcode");
        int code = codeNode.asInt(0);
        if (code != 0) {
            String message = response.has("msg") ? response.path("msg").asText("") : response.path("errmsg").asText("");
            throw new ExternalKnowledgeException("ima 返回错误（" + code + "）" + (message.isBlank() ? "" : "：" + message));
        }
        return response.path("data");
    }

    static String stripMarkup(String text) {
        return text == null ? "" : text.replaceAll("<[^>]{0,40}>", "").replaceAll("\\s+", " ").trim();
    }
}
