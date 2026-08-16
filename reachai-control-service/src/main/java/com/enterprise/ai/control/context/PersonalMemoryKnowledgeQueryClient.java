package com.enterprise.ai.control.context;

import com.enterprise.ai.control.internalauth.InternalServiceAuthSigner;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Optional scalable retrieval path. Callers must still revalidate every returned id in Control. */
@Component
public class PersonalMemoryKnowledgeQueryClient {

    static final String QUERY_PATH = "/internal/knowledge/personal-memories/query";
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
    private final InternalServiceAuthSigner signer;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String knowledgeServiceUrl;
    private final PersonalMemoryKnowledgeQueryMode mode;

    public PersonalMemoryKnowledgeQueryClient(
            InternalServiceAuthSigner signer,
            ObjectMapper objectMapper,
            @Value("${services.knowledge-service.url:http://localhost:18602}") String knowledgeServiceUrl,
            @Value("${reachai.context.personal-memory.knowledge-query-mode:false}") String mode) {
        this.signer = signer;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        this.knowledgeServiceUrl = normalize(knowledgeServiceUrl);
        this.mode = PersonalMemoryKnowledgeQueryMode.parse(mode);
    }

    public QueryAttempt query(String tenantId, String runtimeUserId, String query, int topK, int maxChars) {
        if (!mode.queriesKnowledge()) return QueryAttempt.off();
        long startedAt = System.nanoTime();
        try {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("tenantId", tenantId);
            request.put("runtimeUserId", runtimeUserId);
            request.put("query", query == null ? "" : query);
            request.put("topK", topK);
            request.put("maxChars", maxChars);
            byte[] body = objectMapper.writeValueAsBytes(request);
            Map<String, String> headers = signer.sign("POST", QUERY_PATH, "MEMORY_INDEXER",
                    tenantId, runtimeUserId, body);
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(knowledgeServiceUrl + "/ai" + QUERY_PATH))
                    .timeout(Duration.ofSeconds(3))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body));
            headers.forEach(builder::header);
            HttpResponse<java.io.InputStream> response = httpClient.send(
                    builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                close(response.body());
                return failed(startedAt, "HTTP_ERROR");
            }
            byte[] responseBody;
            try (java.io.InputStream input = response.body()) {
                responseBody = input == null ? new byte[0] : input.readNBytes(1_048_577);
            }
            if (responseBody.length > 1_048_576) return failed(startedAt, "OVERSIZED_RESPONSE");
            Map<String, Object> parsed = objectMapper.readValue(responseBody, MAP);
            Object rawHits = parsed.get("hits");
            if (!(rawHits instanceof Iterable<?> hits)) return failed(startedAt, "INVALID_RESPONSE");
            java.util.ArrayList<Long> ids = new java.util.ArrayList<>();
            for (Object raw : hits) {
                if (!(raw instanceof Map<?, ?> hit)) continue;
                Object id = hit.get("memoryId");
                if (id instanceof Number number) ids.add(number.longValue());
                else if (id != null) try { ids.add(Long.parseLong(String.valueOf(id))); } catch (Exception ignored) { }
            }
            return new QueryAttempt(mode, true, true, List.copyOf(ids),
                    elapsed(startedAt), "SUCCESS");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return failed(startedAt, "INTERRUPTED");
        } catch (Exception ex) {
            return failed(startedAt, "ERROR");
        }
    }

    private QueryAttempt failed(long startedAt, String outcome) {
        return new QueryAttempt(mode, true, false, List.of(), elapsed(startedAt), outcome);
    }

    private static long elapsed(long startedAt) {
        return Math.max(0L, System.nanoTime() - startedAt);
    }

    private static void close(java.io.InputStream input) {
        if (input == null) return;
        try {
            input.close();
        } catch (Exception ignored) {
        }
    }

    private static String normalize(String value) {
        String result = value == null ? "" : value.trim();
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }

    public record QueryAttempt(PersonalMemoryKnowledgeQueryMode mode,
                               boolean attempted,
                               boolean successful,
                               List<Long> ids,
                               long elapsedNanos,
                               String outcome) {
        public QueryAttempt {
            ids = ids == null ? List.of() : List.copyOf(ids);
            outcome = outcome == null || outcome.isBlank() ? "ERROR" : outcome;
        }

        static QueryAttempt off() {
            return new QueryAttempt(PersonalMemoryKnowledgeQueryMode.OFF,
                    false, true, List.of(), 0L, "OFF");
        }
    }
}
