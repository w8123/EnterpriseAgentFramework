package com.enterprise.ai.control.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
import java.util.Map;

/** Relays Runtime's SSE response without buffering it inside OpenFeign. */
@Service
public class RuntimeAgentStreamProxy {

    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(10);

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String runtimeServiceUrl;

    @Autowired
    public RuntimeAgentStreamProxy(
            ObjectMapper objectMapper,
            @Value("${services.runtime-service.url:http://localhost:18604}") String runtimeServiceUrl) {
        this(objectMapper, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build(), runtimeServiceUrl);
    }

    RuntimeAgentStreamProxy(ObjectMapper objectMapper, HttpClient httpClient, String runtimeServiceUrl) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.runtimeServiceUrl = normalizeBaseUrl(runtimeServiceUrl);
    }

    public void stream(Map<String, Object> body, OutputStream outputStream) throws IOException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(runtimeServiceUrl + "/api/runtime/agents/execute/stream"))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(objectMapper.writeValueAsBytes(body)))
                .build();
        try {
            HttpResponse<InputStream> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String responseBody;
                try (InputStream input = response.body()) {
                    responseBody = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                }
                writeProxyError(outputStream, response.statusCode(), responseBody);
                return;
            }
            try (InputStream input = response.body()) {
                input.transferTo(outputStream);
                outputStream.flush();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Runtime Agent stream interrupted", ex);
        }
    }

    private void writeProxyError(OutputStream outputStream, int status, String responseBody) throws IOException {
        Map<String, Object> error = Map.of(
                "code", "RUNTIME_AGENT_STREAM_PROXY_FAILED",
                "message", "Runtime Agent stream returned HTTP " + status,
                "detail", responseBody == null ? "" : responseBody);
        String event = "event: execution.error\n"
                + "data: " + objectMapper.writeValueAsString(error) + "\n\n";
        outputStream.write(event.getBytes(StandardCharsets.UTF_8));
        outputStream.flush();
    }

    private String normalizeBaseUrl(String value) {
        String normalized = value == null ? "" : value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
