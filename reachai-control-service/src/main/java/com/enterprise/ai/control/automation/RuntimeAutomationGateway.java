package com.enterprise.ai.control.automation;

import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.control.internalauth.InternalServiceAuthSigner;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
final class RuntimeAutomationGateway {

    private static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;

    private final InternalServiceAuthSigner signer;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String runtimeBaseUrl;

    RuntimeAutomationGateway(
            InternalServiceAuthSigner signer,
            ObjectMapper objectMapper,
            @Value("${services.runtime-service.url:http://localhost:18604}") String runtimeBaseUrl) {
        this.signer = signer;
        this.objectMapper = objectMapper;
        this.runtimeBaseUrl = normalizeBase(runtimeBaseUrl);
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    ResponseEntity<Object> get(String path, Map<String, ?> query, String actorId) {
        return request("GET", path, query, null, actorId);
    }

    ResponseEntity<Object> post(String path, Object body, String actorId) {
        return request("POST", path, Map.of(), body, actorId);
    }

    ResponseEntity<Object> put(String path, Object body, String actorId) {
        return request("PUT", path, Map.of(), body, actorId);
    }

    ResponseEntity<Object> delete(String path, Map<String, ?> query, String actorId) {
        return request("DELETE", path, query, null, actorId);
    }

    private ResponseEntity<Object> request(String method,
                                           String path,
                                           Map<String, ?> query,
                                           Object body,
                                           String actorId) {
        byte[] payload = write(body);
        Map<String, String> headers = signer.sign(
                method,
                path,
                InternalServiceAuthHeaders.IDENTITY_SOURCE_PLATFORM_SESSION,
                "default",
                actorId,
                payload);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(runtimeBaseUrl + path + query(query)))
                .timeout(Duration.ofSeconds(45))
                .header("Accept", "application/json");
        if (payload.length > 0) builder.header("Content-Type", "application/json");
        headers.forEach(builder::header);
        builder.method(method, payload.length == 0
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(payload));
        try {
            HttpResponse<InputStream> response = httpClient.send(
                    builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            byte[] responseBytes;
            try (InputStream input = response.body()) {
                responseBytes = readBounded(input);
            }
            Object parsed = responseBytes.length == 0 ? null : objectMapper.readValue(responseBytes, Object.class);
            return ResponseEntity.status(response.statusCode()).body(parsed);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return unavailable("AUTOMATION_RUNTIME_INTERRUPTED");
        } catch (Exception unavailable) {
            return unavailable("AUTOMATION_RUNTIME_UNAVAILABLE");
        }
    }

    private byte[] write(Object body) {
        if (body == null) return new byte[0];
        try {
            return objectMapper.writeValueAsBytes(body);
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Automation request cannot be serialized", invalid);
        }
    }

    private byte[] readBounded(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        for (int read; (read = input.read(buffer)) >= 0;) {
            total += read;
            if (total > MAX_RESPONSE_BYTES) {
                throw new IllegalStateException("Runtime Automation response exceeded 8 MiB");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private String query(Map<String, ?> values) {
        if (values == null || values.isEmpty()) return "";
        List<String> pairs = new ArrayList<>();
        values.forEach((key, value) -> {
            if (value != null && StringUtils.hasText(String.valueOf(value))) {
                pairs.add(encode(key) + "=" + encode(String.valueOf(value)));
            }
        });
        return pairs.isEmpty() ? "" : "?" + String.join("&", pairs);
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private ResponseEntity<Object> unavailable(String code) {
        return ResponseEntity.status(503).body(Map.of(
                "success", false,
                "code", code,
                "message", "Runtime Automation service is unavailable"));
    }

    private String normalizeBase(String value) {
        String normalized = value == null ? "" : value.trim();
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            throw new IllegalArgumentException("services.runtime-service.url must be HTTP(S)");
        }
        while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        return normalized;
    }
}
