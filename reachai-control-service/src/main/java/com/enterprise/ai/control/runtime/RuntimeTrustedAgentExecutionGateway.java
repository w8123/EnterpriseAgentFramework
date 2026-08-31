package com.enterprise.ai.control.runtime;

import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.control.client.runtime.RuntimeAgentExecutionInternalClient;
import com.enterprise.ai.control.internalauth.InternalServiceAuthSigner;
import com.enterprise.ai.control.context.PersonalMemoryRecallService;
import com.enterprise.ai.control.context.PersonalMemoryCandidateObservationService;
import com.enterprise.ai.control.context.PersonalMemoryCandidateService.ObservationCommand;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders.IDENTITY_SOURCE_A2A_REMOTE_AGENT;

/**
 * Serializes the trusted execute envelope once, signs those exact bytes, and transmits them.
 * Avoids Feign re-serialization digest drift between Control and Runtime.
 */
@Service
public class RuntimeTrustedAgentExecutionGateway {

    private static final int MAX_TRUSTED_RESPONSE_BYTES = 8 * 1024 * 1024;

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final InternalServiceAuthSigner signer;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String runtimeServiceUrl;
    private final RuntimeAgentStreamProxy streamProxy;
    private final PersonalMemoryRecallService personalMemoryRecallService;
    private final PersonalMemoryCandidateObservationService personalMemoryCandidateObservationService;

    public RuntimeTrustedAgentExecutionGateway(
            InternalServiceAuthSigner signer,
            ObjectMapper objectMapper,
            RuntimeAgentStreamProxy streamProxy,
            @Value("${services.runtime-service.url:http://localhost:18604}") String runtimeServiceUrl) {
        this(signer, objectMapper, streamProxy, runtimeServiceUrl, null, null);
    }

    public RuntimeTrustedAgentExecutionGateway(
            InternalServiceAuthSigner signer,
            ObjectMapper objectMapper,
            RuntimeAgentStreamProxy streamProxy,
            @Value("${services.runtime-service.url:http://localhost:18604}") String runtimeServiceUrl,
            PersonalMemoryRecallService personalMemoryRecallService) {
        this(signer, objectMapper, streamProxy, runtimeServiceUrl, personalMemoryRecallService, null);
    }

    @Autowired
    public RuntimeTrustedAgentExecutionGateway(
            InternalServiceAuthSigner signer,
            ObjectMapper objectMapper,
            RuntimeAgentStreamProxy streamProxy,
            @Value("${services.runtime-service.url:http://localhost:18604}") String runtimeServiceUrl,
            PersonalMemoryRecallService personalMemoryRecallService,
            PersonalMemoryCandidateObservationService personalMemoryCandidateObservationService) {
        this.signer = signer;
        this.objectMapper = objectMapper;
        this.streamProxy = streamProxy;
        this.personalMemoryRecallService = personalMemoryRecallService;
        this.personalMemoryCandidateObservationService = personalMemoryCandidateObservationService;
        this.runtimeServiceUrl = normalizeBaseUrl(runtimeServiceUrl);
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    }

    public ResponseEntity<Map<String, Object>> executeTrusted(Map<String, Object> body,
                                                              String identitySource,
                                                              String identityUserId) {
        return executeTrusted(body, identitySource, trustedTenant(identitySource, body), identityUserId);
    }

    public ResponseEntity<Map<String, Object>> executeTrusted(Map<String, Object> body,
                                                              String identitySource,
                                                              String identityTenantId,
                                                              String identityUserId) {
        try {
            byte[] payload = serializeEnvelope(body, identitySource, identityTenantId, identityUserId);
            Map<String, String> headers = signer.sign(
                    "POST",
                    RuntimeAgentExecutionInternalClient.EXECUTE_PATH,
                    identitySource,
                    identityTenantId,
                    identityUserId,
                    payload);
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(runtimeServiceUrl + RuntimeAgentExecutionInternalClient.EXECUTE_PATH))
                    .timeout(Duration.ofMinutes(3))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json");
            headers.forEach(builder::header);
            HttpRequest request = builder.POST(HttpRequest.BodyPublishers.ofByteArray(payload)).build();
            HttpResponse<java.io.InputStream> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofInputStream());
            byte[] responseBytes;
            try (java.io.InputStream responseStream = response.body()) {
                responseBytes = readBounded(responseStream);
            }
            Map<String, Object> responseBody = parseMap(responseBytes);
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                observeCompletedTurn(body, responseBody, identitySource, identityTenantId, identityUserId);
            }
            return ResponseEntity.status(response.statusCode()).body(responseBody);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("trusted execute interrupted", ex);
        } catch (IOException ex) {
            throw new IllegalStateException("trusted execute failed", ex);
        }
    }

    /**
     * Signs the final stream envelope bytes and relays Runtime SSE.
     * Callers must not invent trust without this method.
     */
    public void streamTrusted(Map<String, Object> body,
                              String identitySource,
                              String identityUserId,
                              OutputStream outputStream,
                              SseStreamRelay.FrameHandler handler) throws IOException {
        streamTrusted(body, identitySource, trustedTenant(identitySource, body), identityUserId, outputStream, handler);
    }

    public void streamTrusted(Map<String, Object> body,
                              String identitySource,
                              String identityTenantId,
                              String identityUserId,
                              OutputStream outputStream,
                              SseStreamRelay.FrameHandler handler) throws IOException {
        byte[] payload = serializeEnvelope(body, identitySource, identityTenantId, identityUserId);
        Map<String, String> headers = signer.sign(
                "POST",
                RuntimeAgentExecutionInternalClient.EXECUTE_STREAM_PATH,
                identitySource,
                identityTenantId,
                identityUserId,
                payload);
        java.util.concurrent.atomic.AtomicBoolean observed = new java.util.concurrent.atomic.AtomicBoolean();
        SseStreamRelay.FrameHandler observingHandler = (eventName, data, downstream) -> {
            // Observe before forwarding completion. An explicit "remember" failure therefore
            // terminates the stream instead of sending a false successful completion event.
            if ("execution.completed".equals(eventName) && observed.compareAndSet(false, true)) {
                Map<String, Object> responseBody = parseMapQuietly(data);
                observeCompletedTurn(body, responseBody, identitySource, identityTenantId, identityUserId);
            }
            handler.handle(eventName, data, downstream);
        };
        streamProxy.streamTrustedAgentExecute(payload, headers, outputStream, observingHandler);
    }

    /**
     * Clears only the authenticated caller's session through the Runtime-owned
     * internal boundary. The empty DELETE body and encoded path are signed exactly
     * as transmitted so a session id cannot be swapped in transit.
     */
    public ResponseEntity<Void> clearTrusted(String sessionId,
                                             String identitySource,
                                             String identityTenantId,
                                             String identityUserId) {
        if (!StringUtils.hasText(sessionId) || sessionId.trim().length() > 128) {
            throw new IllegalArgumentException("sessionId is required and must be at most 128 characters");
        }
        String path = RuntimeAgentExecutionInternalClient.SESSION_PATH_PREFIX
                + UriUtils.encodePathSegment(sessionId.trim(), StandardCharsets.UTF_8);
        byte[] payload = new byte[0];
        Map<String, String> headers = signer.sign(
                "DELETE", path, identitySource, identityTenantId, identityUserId, payload);
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(runtimeServiceUrl + path))
                    .timeout(Duration.ofSeconds(30))
                    .header("Accept", "application/json");
            headers.forEach(builder::header);
            HttpResponse<Void> response = httpClient.send(
                    builder.DELETE().build(), HttpResponse.BodyHandlers.discarding());
            return ResponseEntity.status(response.statusCode()).build();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("trusted session clear interrupted", ex);
        } catch (IOException ex) {
            throw new IllegalStateException("trusted session clear failed", ex);
        }
    }

    byte[] serializeEnvelope(Map<String, Object> body,
                             String identitySource,
                             String identityTenantId,
                             String identityUserId) throws IOException {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("body", body == null ? Map.of() : body);
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("source", identitySource == null ? "" : identitySource);
        identity.put("tenantId", identityTenantId == null ? "default" : identityTenantId);
        identity.put("userId", identityUserId == null ? "" : identityUserId);
        envelope.put("identity", identity);
        if (personalMemoryRecallService != null
                && !IDENTITY_SOURCE_A2A_REMOTE_AGENT.equalsIgnoreCase(identitySource)) {
            Map<String, Object> personalMemory = personalMemoryRecallService.recall(
                    identitySource, identityTenantId, identityUserId, body);
            if (personalMemory != null && !personalMemory.isEmpty()) {
                envelope.put("personalMemory", personalMemory);
            }
        }
        return objectMapper.writeValueAsBytes(envelope);
    }

    private Map<String, Object> parseMap(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0) {
            return Map.of();
        }
        Map<String, Object> parsed = objectMapper.readValue(bytes, MAP_TYPE);
        return parsed == null ? Map.of() : parsed;
    }

    private static byte[] readBounded(java.io.InputStream input) throws IOException {
        if (input == null) {
            return new byte[0];
        }
        byte[] bytes = input.readNBytes(MAX_TRUSTED_RESPONSE_BYTES + 1);
        if (bytes.length > MAX_TRUSTED_RESPONSE_BYTES) {
            throw new IOException("trusted execute response exceeded the configured safety limit");
        }
        return bytes;
    }

    private Map<String, Object> parseMapQuietly(String value) {
        if (!StringUtils.hasText(value)) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(value, MAP_TYPE);
            return parsed == null ? Map.of() : parsed;
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private void observeCompletedTurn(Map<String, Object> body,
                                      Map<String, Object> responseBody,
                                      String identitySource,
                                      String identityTenantId,
                                      String identityUserId) {
        if (IDENTITY_SOURCE_A2A_REMOTE_AGENT.equalsIgnoreCase(identitySource)
                || personalMemoryCandidateObservationService == null || body == null
                || !Boolean.TRUE.equals(booleanValue(responseBody == null ? null : responseBody.get("success")))) {
            return;
        }
        String userMessage = firstText(body, "message", "userInput", "input");
        if (!StringUtils.hasText(userMessage)) {
            return;
        }
        Map<String, Object> metadata = mapValue(responseBody == null ? null : responseBody.get("metadata"));
        ObservationCommand observation = new ObservationCommand(
                        userMessage,
                        stringValue(responseBody, "answer"),
                        firstText(body, "sessionId", "conversationId"),
                        firstText(responseBody, "traceId", stringValue(metadata, "traceId"),
                                stringValue(body, "traceId")),
                        stringValue(body, "agentId"),
                        identitySource,
                        booleanValue(body.get("contributeMemory")));
        if (!personalMemoryCandidateObservationService.observeExplicitIfPresent(
                identitySource, identityTenantId, identityUserId, observation)) {
            personalMemoryCandidateObservationService.observePassive(
                    identitySource, identityTenantId, identityUserId, observation);
        }
    }

    private static Map<String, Object> mapValue(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private static String firstText(Map<String, Object> body, String... keys) {
        for (String key : keys) {
            String value = stringValue(body, key);
            if (StringUtils.hasText(value)) return value;
        }
        return null;
    }

    private static String firstText(Map<String, Object> primary, String primaryKey,
                                    String fallback, String finalFallback) {
        String value = stringValue(primary, primaryKey);
        if (StringUtils.hasText(value)) return value;
        if (StringUtils.hasText(fallback)) return fallback;
        return finalFallback;
    }

    private static String stringValue(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        return value instanceof String text && StringUtils.hasText(text) ? text.trim() : null;
    }

    private static Boolean booleanValue(Object value) {
        if (value == null) return null;
        if (value instanceof Boolean bool) return bool;
        if ("true".equalsIgnoreCase(String.valueOf(value))) return true;
        if ("false".equalsIgnoreCase(String.valueOf(value))) return false;
        return null;
    }

    private static String normalizeBaseUrl(String value) {
        String normalized = value == null ? "" : value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static String trustedTenant(String identitySource, Map<String, Object> body) {
        if (!"EMBED_SESSION".equalsIgnoreCase(identitySource) || body == null) {
            return "default";
        }
        Object value = body.get("tenantId");
        String tenantId = value == null ? "" : String.valueOf(value).trim();
        return tenantId.isEmpty() ? "default" : tenantId;
    }
}
