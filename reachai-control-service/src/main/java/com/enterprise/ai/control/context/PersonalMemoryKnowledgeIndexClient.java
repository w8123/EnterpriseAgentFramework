package com.enterprise.ai.control.context;

import com.enterprise.ai.control.internalauth.InternalServiceAuthSigner;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** Exact-byte signed Control client for the Knowledge personal-memory projection. */
@Component
public class PersonalMemoryKnowledgeIndexClient {

    static final String EVENT_PATH = "/internal/knowledge/personal-memories/events";
    private final InternalServiceAuthSigner signer;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String knowledgeServiceUrl;

    public PersonalMemoryKnowledgeIndexClient(
            InternalServiceAuthSigner signer,
            ObjectMapper objectMapper,
            @Value("${services.knowledge-service.url:http://localhost:18602}") String knowledgeServiceUrl) {
        this.signer = signer;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        this.knowledgeServiceUrl = normalize(knowledgeServiceUrl);
    }

    public void publish(ContextMemoryOutboxEntity event) throws IOException, InterruptedException {
        if (event == null || event.getPayloadJson() == null
                || event.getPayloadJson().length() > 1_000_000) {
            throw new IOException("personal memory index event payload is invalid");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventId", event.getEventId());
        body.put("eventType", event.getEventType());
        body.put("payloadJson", event.getPayloadJson());
        byte[] bytes = objectMapper.writeValueAsBytes(body);
        JsonNode payload = objectMapper.readTree(event.getPayloadJson());
        String tenantId = requiredOwner(payload, "tenantId");
        String runtimeUserId = requiredOwner(payload, "runtimeUserId");
        Map<String, String> headers = signer.sign("POST", EVENT_PATH, "MEMORY_INDEXER",
                tenantId, runtimeUserId, bytes);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(knowledgeServiceUrl + "/ai" + EVENT_PATH))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes));
        headers.forEach(builder::header);
        HttpResponse<Void> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Knowledge personal memory index returned HTTP " + response.statusCode());
        }
    }

    private static String normalize(String value) {
        String result = value == null ? "" : value.trim();
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }

    private static String requiredOwner(JsonNode payload, String field) throws IOException {
        JsonNode value = payload == null ? null : payload.get(field);
        if (value == null || value.asText().isBlank()) {
            throw new IOException("personal memory index event owner is missing");
        }
        return value.asText().trim();
    }
}
