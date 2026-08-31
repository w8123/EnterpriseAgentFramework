package com.enterprise.ai.runtime.client.control;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;

/** Exact-byte, no-retry Runtime client for Control-owned outbound A2A delegation. */
@Component
public class RuntimeA2aControlClient {

    public static final String SEND_PATH =
            "/internal/control/a2a-hub/delegations/message:send";
    private static final int MAX_RESPONSE_BYTES = 32 * 1024 * 1024;

    private final RuntimeControlInternalAuthSigner signer;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String controlServiceUrl;

    public RuntimeA2aControlClient(
            RuntimeControlInternalAuthSigner signer,
            ObjectMapper objectMapper,
            @Value("${services.control-service.url:http://localhost:18603}")
            String controlServiceUrl) {
        this.signer = signer;
        this.objectMapper = objectMapper.copy()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.controlServiceUrl = normalizeBase(controlServiceUrl);
    }

    public SendResponse send(SendRequest request, long timeoutMs) {
        byte[] exactBody = write(request);
        Map<String, String> headers = signer.sign("POST", SEND_PATH, exactBody);
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(controlServiceUrl + SEND_PATH))
                    .timeout(Duration.ofMillis(clientTimeout(timeoutMs)))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("Accept-Encoding", "identity");
            headers.forEach(builder::header);
            HttpResponse<InputStream> response = httpClient.send(
                    builder.POST(HttpRequest.BodyPublishers.ofByteArray(exactBody)).build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            byte[] body;
            try (InputStream stream = response.body()) {
                body = stream == null ? new byte[0] : stream.readNBytes(MAX_RESPONSE_BYTES + 1);
            }
            if (body.length > MAX_RESPONSE_BYTES) {
                throw new CallException("A2A_CONTROL_RESPONSE_TOO_LARGE",
                        "Control outbound delegation response exceeded the Runtime safety limit");
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw problem(response.statusCode(), body);
            }
            SendResponse result = objectMapper.readValue(body, SendResponse.class);
            if (result == null || result.taskId() == null || result.state() == null) {
                throw new CallException("A2A_CONTROL_RESPONSE_INVALID",
                        "Control returned an invalid outbound delegation response");
            }
            return result;
        } catch (CallException known) {
            throw known;
        } catch (HttpTimeoutException | ConnectException failure) {
            throw new CallException("A2A_CONTROL_DELIVERY_UNCERTAIN",
                    "Control outbound delegation outcome is uncertain; Runtime will not retry", failure);
        } catch (Exception failure) {
            throw new CallException("A2A_CONTROL_CALL_FAILED",
                    "Control outbound delegation call failed safely", failure);
        }
    }

    private CallException problem(int status, byte[] body) {
        String code = "A2A_CONTROL_HTTP_" + status;
        try {
            JsonNode parsed = objectMapper.readTree(body);
            String declared = parsed == null ? null : parsed.path("code").asText(null);
            if (declared != null && declared.matches("[A-Z0-9_]{3,96}")) code = declared;
        } catch (Exception ignored) {
            // The remote body is deliberately not included in errors or logs.
        }
        return new CallException(code,
                "Control rejected outbound A2A delegation with HTTP " + status);
    }

    private byte[] write(Object value) {
        try {
            return objectMapper.writeValueAsBytes(value);
        } catch (Exception failure) {
            throw new CallException("A2A_CONTROL_REQUEST_INVALID",
                    "Runtime could not serialize the outbound delegation request", failure);
        }
    }

    private long clientTimeout(long operationTimeoutMs) {
        long normalized = Math.max(1_000L, operationTimeoutMs);
        long withTransportAllowance = normalized > Long.MAX_VALUE - 30_000L
                ? Long.MAX_VALUE : normalized + 30_000L;
        return Math.min(withTransportAllowance, 10 * 60_000L);
    }

    private String normalizeBase(String value) {
        String normalized = value == null ? "" : value.trim();
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            throw new IllegalArgumentException("services.control-service.url must be HTTP(S)");
        }
        while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        return normalized;
    }

    public record SendRequest(
            Long bindingId,
            String runtimeAgentId,
            Long agentConfigVersionId,
            Long principalId,
            Long remoteAgentId,
            Long remoteAgentRevisionId,
            String runtimeSessionId,
            String contextId,
            String taskId,
            String messageId,
            String text,
            String protocolSkillId,
            String contentClassification,
            java.util.List<String> acceptedOutputModes,
            Integer historyLength,
            Long timeoutMs,
            String traceId) {
        public SendRequest {
            acceptedOutputModes = acceptedOutputModes == null
                    ? java.util.List.of() : java.util.List.copyOf(acceptedOutputModes);
        }
    }

    public record SendResponse(
            String schema,
            String taskId,
            String contextId,
            String remoteTaskId,
            String remoteContextId,
            String state,
            String safeSummary,
            String errorCode,
            boolean idempotentReplay,
            java.util.List<String> agentMessages,
            java.util.List<String> artifacts) {
        public SendResponse {
            agentMessages = agentMessages == null ? java.util.List.of() : java.util.List.copyOf(agentMessages);
            artifacts = artifacts == null ? java.util.List.of() : java.util.List.copyOf(artifacts);
        }
    }

    public static final class CallException extends RuntimeException {
        private final String code;

        public CallException(String code, String message) {
            super(message);
            this.code = code;
        }

        public CallException(String code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
