package com.enterprise.ai.control.context;

import com.enterprise.ai.control.internalauth.InternalServiceAuthSigner;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/** Fail-closed Control client for aggregate-only Knowledge owner-erasure proof. */
@Component
public class PersonalMemoryKnowledgeErasureClient {

    static final String OWNER_STATUS_PATH = "/internal/knowledge/personal-memories/owner-status";
    private static final int MAX_RESPONSE_BYTES = 1_048_576;
    private final InternalServiceAuthSigner signer;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String knowledgeServiceUrl;

    @Autowired
    public PersonalMemoryKnowledgeErasureClient(
            InternalServiceAuthSigner signer,
            ObjectMapper objectMapper,
            @Value("${services.knowledge-service.url:http://localhost:18602}")
            String knowledgeServiceUrl) {
        this(signer, objectMapper, knowledgeServiceUrl,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }

    PersonalMemoryKnowledgeErasureClient(
            InternalServiceAuthSigner signer,
            ObjectMapper objectMapper,
            String knowledgeServiceUrl,
            HttpClient httpClient) {
        this.signer = signer;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.knowledgeServiceUrl = normalize(knowledgeServiceUrl);
    }

    public OwnerProjectionStatus status(String tenantId, String runtimeUserId) {
        try {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("tenantId", tenantId);
            request.put("runtimeUserId", runtimeUserId);
            byte[] body = objectMapper.writeValueAsBytes(request);
            Map<String, String> headers = signer.sign(
                    "POST", OWNER_STATUS_PATH, "MEMORY_INDEXER",
                    tenantId, runtimeUserId, body);
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(knowledgeServiceUrl + "/ai" + OWNER_STATUS_PATH))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body));
            headers.forEach(builder::header);
            HttpResponse<java.io.InputStream> response = httpClient.send(
                    builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            byte[] responseBody;
            try (java.io.InputStream input = response.body()) {
                responseBody = input == null ? new byte[0]
                        : input.readNBytes(MAX_RESPONSE_BYTES + 1);
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("Knowledge owner status returned HTTP "
                        + response.statusCode());
            }
            if (responseBody.length == 0 || responseBody.length > MAX_RESPONSE_BYTES) {
                throw new IOException("Knowledge owner status response is invalid");
            }
            OwnerProjectionStatus result = objectMapper.readValue(
                    responseBody, OwnerProjectionStatus.class);
            validate(result);
            return result;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Knowledge owner status call interrupted", interrupted);
        } catch (IOException failure) {
            throw new IllegalStateException("Knowledge owner status call failed", failure);
        }
    }

    private static void validate(OwnerProjectionStatus result) throws IOException {
        if (result == null || result.runtimeUserHash() == null
                || !result.runtimeUserHash().matches("[0-9a-f]{64}")
                || result.totalCount() < 0 || result.activeCount() < 0
                || result.deletedCount() < 0 || result.unsafeDeletedVectorCount() < 0
                || result.totalCount() != result.activeCount() + result.deletedCount()
                || result.projectionErased()
                != (result.activeCount() == 0 && result.unsafeDeletedVectorCount() == 0)) {
            throw new IOException("Knowledge owner status response failed validation");
        }
    }

    private static String normalize(String value) {
        String result = value == null ? "" : value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    public record OwnerProjectionStatus(
            String runtimeUserHash,
            long totalCount,
            long activeCount,
            long deletedCount,
            long unsafeDeletedVectorCount,
            Long maxSourceVersion,
            LocalDateTime latestUpdatedAt,
            boolean projectionErased) {
    }
}
