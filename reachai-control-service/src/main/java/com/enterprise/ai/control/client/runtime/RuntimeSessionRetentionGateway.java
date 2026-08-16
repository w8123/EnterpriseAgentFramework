package com.enterprise.ai.control.client.runtime;

import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.control.internalauth.InternalServiceAuthSigner;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriUtils;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/** Exact-byte HMAC client for Control-administered, Runtime-owned session retention. */
@Service
public class RuntimeSessionRetentionGateway {

    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private static final String ROOT = "/internal/runtime/session-retention";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final InternalServiceAuthSigner signer;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String runtimeServiceUrl;

    @Autowired
    public RuntimeSessionRetentionGateway(
            InternalServiceAuthSigner signer,
            ObjectMapper objectMapper,
            @Value("${services.runtime-service.url:http://localhost:18604}") String runtimeServiceUrl) {
        this(signer, objectMapper, runtimeServiceUrl,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build());
    }

    RuntimeSessionRetentionGateway(
            InternalServiceAuthSigner signer,
            ObjectMapper objectMapper,
            String runtimeServiceUrl,
            HttpClient httpClient) {
        this.signer = signer;
        this.objectMapper = objectMapper;
        this.runtimeServiceUrl = normalizeBaseUrl(runtimeServiceUrl);
        this.httpClient = httpClient;
    }

    public ResponseEntity<Map<String, Object>> policy(String tenantId, String actorId) {
        return exchange("GET", policyPath(tenantId), tenantId, actorId, null);
    }

    public ResponseEntity<Map<String, Object>> savePolicy(
            String tenantId,
            int activeRetentionDays,
            int clearedRetentionHours,
            String actorId) {
        return exchange("PUT", policyPath(tenantId), tenantId, actorId, Map.of(
                "activeRetentionDays", activeRetentionDays,
                "clearedRetentionHours", clearedRetentionHours));
    }

    public ResponseEntity<Map<String, Object>> deletePolicy(String tenantId, String actorId) {
        return exchange("DELETE", policyPath(tenantId), tenantId, actorId, null);
    }

    public ResponseEntity<Map<String, Object>> session(
            String tenantId, String sessionId, String actorId) {
        return exchange("GET", sessionPath(tenantId, sessionId), tenantId, actorId, null);
    }

    public ResponseEntity<Map<String, Object>> setLegalHold(
            String tenantId,
            String sessionId,
            boolean enabled,
            String reasonCode,
            String referenceId,
            String actorId) {
        java.util.LinkedHashMap<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("enabled", enabled);
        body.put("reasonCode", reasonCode);
        if (referenceId != null) {
            body.put("referenceId", referenceId);
        }
        return exchange("PUT", sessionPath(tenantId, sessionId) + "/legal-hold",
                tenantId, actorId, body);
    }

    public ResponseEntity<Map<String, Object>> erase(
            String tenantId,
            String sessionId,
            String reasonCode,
            String referenceId,
            String actorId) {
        java.util.LinkedHashMap<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("reasonCode", reasonCode);
        if (referenceId != null) {
            body.put("referenceId", referenceId);
        }
        return exchange("POST", sessionPath(tenantId, sessionId) + "/erase",
                tenantId, actorId, body);
    }

    public ResponseEntity<Map<String, Object>> eraseOwner(
            String tenantId,
            String runtimeUserId,
            String reasonCode,
            String referenceId,
            Integer batchSize,
            String actorId) {
        java.util.LinkedHashMap<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("runtimeUserId", runtimeUserId);
        body.put("reasonCode", reasonCode);
        if (referenceId != null) {
            body.put("referenceId", referenceId);
        }
        if (batchSize != null) {
            body.put("batchSize", batchSize);
        }
        return exchange("POST", ownerErasurePath(tenantId), tenantId, actorId, body);
    }

    private ResponseEntity<Map<String, Object>> exchange(
            String method,
            String path,
            String tenantId,
            String actorId,
            Map<String, Object> body) {
        try {
            byte[] payload = body == null ? new byte[0] : objectMapper.writeValueAsBytes(body);
            Map<String, String> headers = signer.sign(
                    method,
                    path,
                    InternalServiceAuthHeaders.IDENTITY_SOURCE_PLATFORM_SESSION,
                    tenantId,
                    actorId,
                    payload);
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(runtimeServiceUrl + path))
                    .timeout(Duration.ofSeconds(30))
                    .header("Accept", "application/json");
            if (body != null) {
                builder.header("Content-Type", "application/json");
            }
            headers.forEach(builder::header);
            HttpRequest.BodyPublisher publisher = body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofByteArray(payload);
            HttpResponse<java.io.InputStream> response = httpClient.send(
                    builder.method(method, publisher).build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            byte[] responseBytes;
            try (java.io.InputStream responseStream = response.body()) {
                responseBytes = responseStream.readNBytes(MAX_RESPONSE_BYTES + 1);
            }
            if (responseBytes.length > MAX_RESPONSE_BYTES) {
                throw new IOException("Runtime session retention response exceeded the safety limit");
            }
            Map<String, Object> responseBody = responseBytes.length == 0
                    ? Map.of()
                    : objectMapper.readValue(responseBytes, MAP_TYPE);
            return ResponseEntity.status(response.statusCode()).body(
                    responseBody == null ? Map.of() : responseBody);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Runtime session retention call interrupted", interrupted);
        } catch (IOException failure) {
            throw new IllegalStateException("Runtime session retention call failed", failure);
        }
    }

    private static String policyPath(String tenantId) {
        return ROOT + "/tenants/" + segment(tenantId) + "/policy";
    }

    private static String sessionPath(String tenantId, String sessionId) {
        return ROOT + "/tenants/" + segment(tenantId) + "/sessions/" + segment(sessionId);
    }

    private static String ownerErasurePath(String tenantId) {
        return ROOT + "/tenants/" + segment(tenantId) + "/owner-erasure";
    }

    private static String segment(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Runtime session retention path value is required");
        }
        return UriUtils.encodePathSegment(value.trim(), StandardCharsets.UTF_8);
    }

    private static String normalizeBaseUrl(String value) {
        String normalized = value == null ? "" : value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
