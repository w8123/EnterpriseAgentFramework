package com.enterprise.ai.control.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

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
import java.util.Set;
import java.util.regex.Pattern;

/** Relays Runtime SSE responses without buffering them inside OpenFeign. */
@Service
public class RuntimeAgentStreamProxy {

    public static final String AGENT_EXECUTE_STREAM = "/api/runtime/agents/execute/stream";
    public static final String DEBUG_SESSION_STREAM = "/api/runtime/debug-sessions/stream";

    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(10);
    private static final Pattern DEBUG_SUBMIT_STREAM =
            Pattern.compile("^/api/runtime/debug-sessions/[^/]+/submit/stream$");

    private static final Set<String> EXACT_ALLOWED_PATHS = Set.of(
            AGENT_EXECUTE_STREAM,
            DEBUG_SESSION_STREAM);

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
        stream(AGENT_EXECUTE_STREAM, body, outputStream, SseStreamRelay.passthrough());
    }

    public void streamAgentExecute(Map<String, Object> body,
                                   OutputStream outputStream,
                                   SseStreamRelay.FrameHandler handler) throws IOException {
        stream(AGENT_EXECUTE_STREAM, body, outputStream, handler);
    }

    public void stream(String path, Map<String, Object> body, OutputStream outputStream) throws IOException {
        stream(path, body, outputStream, SseStreamRelay.passthrough());
    }

    public void streamDebugSubmit(String sessionId,
                                  Map<String, Object> body,
                                  OutputStream outputStream) throws IOException {
        if (!StringUtils.hasText(sessionId)) {
            throw new IllegalArgumentException("sessionId is required");
        }
        stream("/api/runtime/debug-sessions/" + sessionId.trim() + "/submit/stream", body, outputStream);
    }

    public void stream(String path,
                       Map<String, Object> body,
                       OutputStream outputStream,
                       SseStreamRelay.FrameHandler handler) throws IOException {
        String allowedPath = requireAllowedPath(path);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(runtimeServiceUrl + allowedPath))
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
                relayUpstream(input, outputStream, handler);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Runtime stream interrupted", ex);
        }
    }

    /**
     * 生产路径：转发 Runtime upstream SSE；下游写失败（Broken pipe 等）时关闭 upstream，
     * 以触发 Runtime 端取消。包可见供单测直接命中真实代理逻辑。
     */
    void relayUpstream(InputStream upstream,
                       OutputStream downstream,
                       SseStreamRelay.FrameHandler handler) throws IOException {
        try {
            SseStreamRelay.relay(upstream, downstream, handler);
        } catch (IOException ex) {
            if (isClientDisconnect(ex)) {
                upstream.close();
                return;
            }
            throw ex;
        }
    }

    private boolean isClientDisconnect(IOException ex) {
        String message = ex.getMessage();
        if (message == null) {
            return false;
        }
        String normalized = message.toLowerCase();
        return normalized.contains("broken pipe")
                || normalized.contains("connection reset")
                || normalized.contains("connection aborted")
                || normalized.contains("forcibly closed");
    }

    private String requireAllowedPath(String path) {
        if (!StringUtils.hasText(path)) {
            throw new IllegalArgumentException("stream path is required");
        }
        String normalized = path.trim();
        if (EXACT_ALLOWED_PATHS.contains(normalized) || DEBUG_SUBMIT_STREAM.matcher(normalized).matches()) {
            return normalized;
        }
        throw new IllegalArgumentException("stream path is not allowlisted: " + normalized);
    }

    private void writeProxyError(OutputStream outputStream, int status, String responseBody) throws IOException {
        Map<String, Object> error = Map.of(
                "code", "RUNTIME_STREAM_PROXY_FAILED",
                "message", "Runtime stream returned HTTP " + status,
                "detail", responseBody == null ? "" : responseBody);
        SseStreamRelay.writeFrame(outputStream, "execution.error",
                objectMapper.writeValueAsString(error));
    }

    private String normalizeBaseUrl(String value) {
        String normalized = value == null ? "" : value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
