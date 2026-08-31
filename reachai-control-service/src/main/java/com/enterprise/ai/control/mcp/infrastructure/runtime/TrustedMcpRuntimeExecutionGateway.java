package com.enterprise.ai.control.mcp.infrastructure.runtime;

import com.enterprise.ai.control.internalauth.InternalServiceAuthSigner;
import com.enterprise.ai.control.mcp.application.port.McpRuntimeExecutionGateway;
import com.enterprise.ai.control.mcp.infrastructure.McpHubProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders.IDENTITY_SOURCE_MCP_REMOTE_CLIENT;

/**
 * Exact-byte HMAC-signed Control→Runtime adapter for MCP tool execution.
 * Trusted identity (MCP_REMOTE_CLIENT) travels only in signed headers; the
 * public body is treated as untrusted routing metadata.
 */
@Component
public class TrustedMcpRuntimeExecutionGateway implements McpRuntimeExecutionGateway {

    private static final String EXECUTE_PATH = "/internal/runtime/mcp/tool-executions";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final InternalServiceAuthSigner signer;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String runtimeServiceUrl;
    private final McpHubProperties properties;

    public TrustedMcpRuntimeExecutionGateway(
            InternalServiceAuthSigner signer,
            ObjectMapper objectMapper,
            McpHubProperties properties,
            @Value("${services.runtime-service.url:http://localhost:18604}") String runtimeServiceUrl) {
        this.signer = signer;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.runtimeServiceUrl = normalizeBaseUrl(runtimeServiceUrl);
    }

    @Override
    public ExecutionOutcome execute(ToolExecutionCommand command) {
        requireCommand(command);
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("sourceKind", command.sourceKind());
        requestBody.put("sourceRef", command.sourceRef());
        if (command.workflowVersionId() != null) {
            requestBody.put("workflowVersionId", command.workflowVersionId());
        }
        requestBody.put("toolName", command.toolName());
        requestBody.put("arguments", command.arguments() == null ? Map.of() : command.arguments());
        requestBody.put("timeoutMs", runtimeDeadlineMs());
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("mcpClientId", command.mcpClientId());
        metadata.put("mcpClientName", command.mcpClientName());
        metadata.put("publicationId", command.publicationId());
        metadata.put("revisionNo", command.revisionNo());
        putIfPresent(metadata, "projectId", command.projectId());
        putIfPresent(metadata, "projectCode", command.projectCode());
        putIfPresent(metadata, "environment", command.environment());
        putIfPresent(metadata, "tenantId", command.tenantId());
        requestBody.put("metadata", metadata);

        byte[] payload = write(requestBody);
        String principalKey = "mcp-client-" + command.mcpClientId();
        Map<String, String> headers = signer.sign(
                "POST", EXECUTE_PATH, IDENTITY_SOURCE_MCP_REMOTE_CLIENT,
                command.tenantId(), principalKey, payload);
        Map<String, Object> result = postJson(EXECUTE_PATH, payload, headers, principalKey);

        boolean success = Boolean.TRUE.equals(result.get("success"));
        return new ExecutionOutcome(
                success,
                text(result.get("code")),
                map(result.get("output")),
                text(result.get("runId")),
                text(result.get("traceId")),
                success ? null : firstText(text(result.get("code")), "MCP_TOOL_EXECUTION_FAILED"));
    }

    private Map<String, Object> postJson(
            String path, byte[] payload, Map<String, String> signedHeaders, String principalKey) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(runtimeServiceUrl + path))
                    .timeout(properties.getExecutionTimeout())
                    .header("Accept", "application/json")
                    .header("Accept-Encoding", "identity");
            signedHeaders.forEach(builder::header);
            if (payload.length > 0) {
                builder.header("Content-Type", "application/json");
            }
            HttpResponse<InputStream> response = httpClient.send(
                    builder.POST(HttpRequest.BodyPublishers.ofByteArray(payload)).build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            byte[] responseBytes;
            try (InputStream input = response.body()) {
                responseBytes = readBounded(input, properties.getMaxExecutionResponseBytes());
            }
            Map<String, Object> responseBody = parse(responseBytes);
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                return responseBody;
            }
            String safeCode = firstText(text(responseBody.get("code")), "MCP_RUNTIME_HTTP_" + status);
            Map<String, Object> failure = new LinkedHashMap<>();
            failure.put("success", false);
            failure.put("code", safeCode);
            return failure;
        } catch (HttpTimeoutException timeoutFailure) {
            return failure("MCP_RUNTIME_TIMEOUT",
                    "Runtime tool execution timed out after dispatch may have started");
        } catch (ConnectException notSent) {
            return failure("MCP_RUNTIME_UNAVAILABLE",
                    "Runtime could not be reached before the request was sent");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return failure("MCP_RUNTIME_INTERRUPTED",
                    "Runtime tool execution was interrupted after dispatch may have started");
        } catch (IOException transport) {
            return failure("MCP_RUNTIME_IO_FAILURE",
                    "Runtime connection failed after dispatch may have started");
        }
    }

    private byte[] readBounded(InputStream input, int maximumBytes) throws IOException {
        if (input == null) {
            return new byte[0];
        }
        byte[] bytes = input.readNBytes(maximumBytes + 1);
        if (bytes.length > maximumBytes) {
            throw new IOException("Runtime tool execution response exceeded the safety limit");
        }
        return bytes;
    }

    private Map<String, Object> parse(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return Map.of();
        }
        try {
            JsonNode root = objectMapper.readTree(bytes);
            if (root == null || !root.isObject()) {
                return Map.of();
            }
            return objectMapper.convertValue(root, MAP_TYPE);
        } catch (IOException invalid) {
            return Map.of();
        }
    }

    private byte[] write(Object value) {
        try {
            return objectMapper.writeValueAsBytes(value);
        } catch (IOException serializationFailure) {
            throw new IllegalStateException("MCP execution request could not be serialized",
                    serializationFailure);
        }
    }

    private void requireCommand(ToolExecutionCommand command) {
        if (command == null
                || !StringUtils.hasText(command.sourceKind())
                || !StringUtils.hasText(command.sourceRef())
                || !StringUtils.hasText(command.toolName())
                || !StringUtils.hasText(command.tenantId())) {
            throw new IllegalArgumentException("tool execution command is invalid");
        }
    }

    private long runtimeDeadlineMs() {
        long configured = properties.getExecutionTimeout().toMillis();
        return Math.max(1_000L, configured - 5_000L);
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null && (!(value instanceof String text) || StringUtils.hasText(text))) {
            target.put(key, value);
        }
    }

    private Map<String, Object> failure(String code, String message) {
        // The message stays out of the JSON-RPC surface; the code is the stable contract.
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", false);
        result.put("code", code);
        return result;
    }

    private Map<String, Object> map(Object value) {
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> result = new LinkedHashMap<>();
            raw.forEach((key, item) -> {
                if (key != null) {
                    result.put(String.valueOf(key), item);
                }
            });
            return result;
        }
        return Map.of();
    }

    private String normalizeBaseUrl(String value) {
        String normalized = value == null ? "" : value.trim();
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            throw new IllegalArgumentException("services.runtime-service.url must be HTTP(S)");
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}
