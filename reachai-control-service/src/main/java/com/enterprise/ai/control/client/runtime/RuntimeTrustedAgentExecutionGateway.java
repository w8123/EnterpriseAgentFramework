package com.enterprise.ai.control.client.runtime;

import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.control.internalauth.InternalServiceAuthSigner;
import com.enterprise.ai.control.runtime.RuntimeAgentStreamProxy;
import com.enterprise.ai.control.runtime.SseStreamRelay;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Serializes the trusted execute envelope once, signs those exact bytes, and transmits them.
 * Avoids Feign re-serialization digest drift between Control and Runtime.
 */
@Service
public class RuntimeTrustedAgentExecutionGateway {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final InternalServiceAuthSigner signer;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String runtimeServiceUrl;
    private final RuntimeAgentStreamProxy streamProxy;

    public RuntimeTrustedAgentExecutionGateway(
            InternalServiceAuthSigner signer,
            ObjectMapper objectMapper,
            RuntimeAgentStreamProxy streamProxy,
            @Value("${services.runtime-service.url:http://localhost:18604}") String runtimeServiceUrl) {
        this.signer = signer;
        this.objectMapper = objectMapper;
        this.streamProxy = streamProxy;
        this.runtimeServiceUrl = normalizeBaseUrl(runtimeServiceUrl);
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    }

    public ResponseEntity<Map<String, Object>> executeTrusted(Map<String, Object> body,
                                                              String identitySource,
                                                              String identityUserId) {
        try {
            byte[] payload = serializeEnvelope(body, identitySource, identityUserId);
            Map<String, String> headers = signer.sign(
                    "POST",
                    RuntimeAgentExecutionInternalClient.EXECUTE_PATH,
                    identitySource,
                    identityUserId,
                    payload);
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(runtimeServiceUrl + RuntimeAgentExecutionInternalClient.EXECUTE_PATH))
                    .timeout(Duration.ofMinutes(3))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json");
            headers.forEach(builder::header);
            HttpRequest request = builder.POST(HttpRequest.BodyPublishers.ofByteArray(payload)).build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            Map<String, Object> responseBody = parseMap(response.body());
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
        byte[] payload = serializeEnvelope(body, identitySource, identityUserId);
        Map<String, String> headers = signer.sign(
                "POST",
                RuntimeAgentExecutionInternalClient.EXECUTE_STREAM_PATH,
                identitySource,
                identityUserId,
                payload);
        streamProxy.streamTrustedAgentExecute(payload, headers, outputStream, handler);
    }

    /** @deprecated Prefer {@link #streamTrusted}; retained for tests that mock header signing. */
    public Map<String, String> signStreamHeaders(String identitySource, String identityUserId) {
        // Empty body digest for header-only tests; production stream uses streamTrusted.
        return signer.sign(
                "POST",
                RuntimeAgentExecutionInternalClient.EXECUTE_STREAM_PATH,
                identitySource,
                identityUserId,
                new byte[0]);
    }

    private byte[] serializeEnvelope(Map<String, Object> body,
                                     String identitySource,
                                     String identityUserId) throws IOException {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("body", body == null ? Map.of() : body);
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("source", identitySource == null ? "" : identitySource);
        identity.put("userId", identityUserId == null ? "" : identityUserId);
        envelope.put("identity", identity);
        return objectMapper.writeValueAsBytes(envelope);
    }

    private Map<String, Object> parseMap(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0) {
            return Map.of();
        }
        Map<String, Object> parsed = objectMapper.readValue(bytes, MAP_TYPE);
        return parsed == null ? Map.of() : parsed;
    }

    private static String normalizeBaseUrl(String value) {
        String normalized = value == null ? "" : value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
