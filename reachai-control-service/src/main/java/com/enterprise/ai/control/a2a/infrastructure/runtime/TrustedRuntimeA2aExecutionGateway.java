package com.enterprise.ai.control.a2a.infrastructure.runtime;

import com.enterprise.ai.control.a2a.application.protocol.A2aProtocolModels.Artifact;
import com.enterprise.ai.control.a2a.application.protocol.A2aProtocolModels.Message;
import com.enterprise.ai.control.a2a.application.protocol.A2aProtocolModels.Part;
import com.enterprise.ai.control.a2a.application.port.A2aRuntimeExecutionGateway;
import com.enterprise.ai.control.a2a.domain.A2aTaskState;
import com.enterprise.ai.control.internalauth.InternalServiceAuthSigner;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriUtils;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders.IDENTITY_SOURCE_A2A_REMOTE_AGENT;

/** Exact-byte signed Control→Runtime adapter dedicated to A2A execution and cancellation. */
@Component
public class TrustedRuntimeA2aExecutionGateway implements A2aRuntimeExecutionGateway {

    private static final String EXECUTE_PATH = "/internal/runtime/a2a/executions";
    private static final String CANCEL_PATH_PREFIX = "/internal/runtime/a2a/executions/";
    private static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final InternalServiceAuthSigner signer;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;
    private final String runtimeServiceUrl;

    public TrustedRuntimeA2aExecutionGateway(
            InternalServiceAuthSigner signer,
            ObjectMapper objectMapper,
            @Value("${services.runtime-service.url:http://localhost:18604}") String runtimeServiceUrl) {
        this.signer = signer;
        this.mapper = objectMapper.copy()
                .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        this.runtimeServiceUrl = normalizeBaseUrl(runtimeServiceUrl);
    }

    @Override
    public ExecutionResult execute(DispatchCommand command) {
        requireCommand(command);
        Message message = readMessage(command.canonicalMessage());
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("executionId", command.executionId());
        requestBody.put("taskId", command.taskId());
        requestBody.put("contextId", command.contextId());
        requestBody.put("agentId", command.agentId());
        requestBody.put("agentConfigVersionId", command.agentConfigVersionId());
        putIfPresent(requestBody, "interactionId", command.interactionId());
        requestBody.put("message", prompt(message));
        requestBody.put("principalType", command.principalType());
        requestBody.put("trustLevel", command.trustLevel());
        requestBody.put("scopes", command.scopes());
        byte[] payload = serialize(requestBody);
        Map<String, Object> result = postJson(
                EXECUTE_PATH, payload, command.tenantScope(), command.principalKey(),
                boundedExecuteTimeout(command.timeoutMs()), "A2A_RUNTIME_EXECUTE");

        Map<String, Object> resultMetadata = map(result.get("metadata"));
        String answer = text(result.get("answer"));
        String code = firstText(text(resultMetadata.get("code")), text(result.get("code")));
        String traceId = firstText(text(result.get("traceId")), text(resultMetadata.get("traceId")),
                command.executionId());
        String runId = firstText(text(result.get("runId")), text(resultMetadata.get("runId")));
        String interactionId = firstText(
                text(result.get("interactionId")), text(resultMetadata.get("interactionId")));
        A2aTaskState state = state(result, resultMetadata, code);
        String summary = "Runtime result: " + state.name();
        OutputResource agentMessage = null;
        OutputResource artifact = null;
        if (StringUtils.hasText(answer)) {
            if (state == A2aTaskState.TASK_STATE_COMPLETED) {
                artifact = artifact(answer, summary);
            } else {
                agentMessage = message(command, answer, summary);
            }
        }
        String errorCode = state == A2aTaskState.TASK_STATE_FAILED
                || state == A2aTaskState.TASK_STATE_REJECTED ? code : null;
        String errorSummary = errorCode == null ? null : summary;
        return new ExecutionResult(state, runId, traceId, interactionId, summary,
                errorCode, errorSummary, agentMessage, artifact);
    }

    @Override
    public CancelResult cancel(CancelCommand command) {
        if (command == null) {
            throw permanent("A2A_RUNTIME_CANCEL_INVALID", "cancel command is required", null);
        }
        String executionId = identifier(command.executionId(), "executionId");
        String path = CANCEL_PATH_PREFIX
                + UriUtils.encodePathSegment(executionId, StandardCharsets.UTF_8) + ":cancel";
        Map<String, Object> response = postJson(
                path, new byte[0], command.tenantScope(), command.principalKey(),
                Duration.ofSeconds(30), "A2A_RUNTIME_CANCEL");
        return new CancelResult(
                truth(response.get("accepted")),
                truth(response.get("active")),
                truth(response.get("queuedBeforeStart")));
    }

    private Map<String, Object> postJson(
            String path,
            byte[] payload,
            String tenantScope,
            String principalKey,
            Duration timeout,
            String operation) {
        byte[] exactPayload = payload == null ? new byte[0] : payload;
        Map<String, String> headers = signer.sign(
                "POST", path, IDENTITY_SOURCE_A2A_REMOTE_AGENT,
                tenantScope, principalKey, exactPayload);
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(runtimeServiceUrl + path))
                    .timeout(timeout)
                    .header("Accept", "application/json");
            if (exactPayload.length > 0) {
                builder.header("Content-Type", "application/json");
            }
            headers.forEach(builder::header);
            HttpResponse<InputStream> response = httpClient.send(
                    builder.POST(HttpRequest.BodyPublishers.ofByteArray(exactPayload)).build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            byte[] responseBytes;
            try (InputStream input = response.body()) {
                responseBytes = readBounded(input);
            }
            Map<String, Object> responseBody = parse(responseBytes);
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                return responseBody;
            }
            String safeCode = firstText(text(responseBody.get("code")), operation + "_HTTP_" + status);
            String safeMessage = "Runtime rejected the A2A call with HTTP " + status;
            if (status == HttpStatus.UNAUTHORIZED.value() || status == HttpStatus.FORBIDDEN.value()
                    || status == HttpStatus.BAD_REQUEST.value() || status == HttpStatus.NOT_FOUND.value()) {
                throw permanent(safeCode, safeMessage, null);
            }
            throw call(FailureKind.OUTCOME_UNKNOWN, safeCode,
                    "Runtime returned an indeterminate A2A response", null);
        } catch (CallException known) {
            throw known;
        } catch (HttpConnectTimeoutException | ConnectException notSent) {
            throw call(FailureKind.DEFINITELY_NOT_SENT, operation + "_UNAVAILABLE",
                    "Runtime could not be reached before the request was sent", notSent);
        } catch (HttpTimeoutException timeoutFailure) {
            throw call(FailureKind.OUTCOME_UNKNOWN, operation + "_TIMEOUT",
                    "Runtime response timed out after dispatch may have started", timeoutFailure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw call(FailureKind.OUTCOME_UNKNOWN, operation + "_INTERRUPTED",
                    "Runtime call was interrupted after dispatch may have started", interrupted);
        } catch (IllegalArgumentException invalidEndpoint) {
            throw permanent(operation + "_ENDPOINT_INVALID",
                    "Runtime service endpoint is invalid", invalidEndpoint);
        } catch (IOException transport) {
            throw call(FailureKind.OUTCOME_UNKNOWN, operation + "_IO_FAILURE",
                    "Runtime connection failed after dispatch may have started", transport);
        }
    }

    private A2aTaskState state(
            Map<String, Object> result, Map<String, Object> metadata, String code) {
        if (truth(result.get("waiting")) || truth(metadata.get("interactionPending"))
                || "SUSPENDED".equalsIgnoreCase(text(metadata.get("status")))) {
            return code != null && code.toUpperCase(Locale.ROOT).contains("AUTH")
                    ? A2aTaskState.TASK_STATE_AUTH_REQUIRED
                    : A2aTaskState.TASK_STATE_INPUT_REQUIRED;
        }
        String normalized = code == null ? "" : code.toUpperCase(Locale.ROOT);
        if (normalized.contains("CANCEL")) {
            return A2aTaskState.TASK_STATE_CANCELED;
        }
        if (normalized.contains("AUTH_REQUIRED")) {
            return A2aTaskState.TASK_STATE_AUTH_REQUIRED;
        }
        if (normalized.contains("FORBIDDEN") || normalized.contains("REJECT")
                || normalized.contains("POLICY") || normalized.contains("GUARD")) {
            return A2aTaskState.TASK_STATE_REJECTED;
        }
        return truth(result.get("success"))
                ? A2aTaskState.TASK_STATE_COMPLETED : A2aTaskState.TASK_STATE_FAILED;
    }

    private String prompt(Message message) {
        List<String> values = new ArrayList<>();
        for (Part part : message.parts()) {
            if (part.text() != null) {
                values.add(part.text());
            } else if (part.data() != null) {
                values.add(part.data().toString());
            } else if (part.url() != null) {
                values.add("Referenced HTTPS resource: " + part.url());
            } else if (part.raw() != null) {
                throw permanent("A2A_UNSUPPORTED_OPERATION",
                        "raw Parts require a governed Runtime attachment adapter", null);
            }
        }
        String prompt = String.join("\n\n", values).trim();
        if (prompt.isBlank()) {
            throw permanent("A2A_MESSAGE_INVALID",
                    "message does not contain executable content", null);
        }
        return prompt;
    }

    private OutputResource artifact(String answer, String summary) {
        String id = opaque("artifact");
        Artifact value = new Artifact(id, "Agent result", null,
                List.of(new Part(answer, null, null, null, null, null, "text/plain")),
                null, null);
        return output(id, value, summary);
    }

    private OutputResource message(DispatchCommand command, String answer, String summary) {
        String id = opaque("msg");
        Message value = new Message(id, command.contextId(), command.taskId(), "ROLE_AGENT",
                List.of(new Part(answer, null, null, null, null, null, "text/plain")),
                null, null, null);
        return output(id, value, summary);
    }

    private OutputResource output(String id, Object value, String summary) {
        byte[] bytes = serialize(value);
        return new OutputResource(id, bytes, sha256(bytes), bytes.length,
                List.of("text/plain"), summary);
    }

    private Message readMessage(byte[] bytes) {
        try {
            return mapper.readValue(bytes, Message.class);
        } catch (Exception exception) {
            throw permanent("A2A_MESSAGE_INVALID", "persisted A2A Message is invalid", exception);
        }
    }

    private byte[] serialize(Object value) {
        try {
            return mapper.writeValueAsBytes(value);
        } catch (JsonProcessingException exception) {
            throw permanent("A2A_RUNTIME_REQUEST_INVALID",
                    "A2A Runtime request could not be serialized", exception);
        }
    }

    private Map<String, Object> parse(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0) {
            return Map.of();
        }
        Map<String, Object> parsed = mapper.readValue(bytes, MAP_TYPE);
        return parsed == null ? Map.of() : parsed;
    }

    private byte[] readBounded(InputStream input) throws IOException {
        if (input == null) {
            return new byte[0];
        }
        byte[] bytes = input.readNBytes(MAX_RESPONSE_BYTES + 1);
        if (bytes.length > MAX_RESPONSE_BYTES) {
            throw new IOException("Runtime A2A response exceeded the safety limit");
        }
        return bytes;
    }

    private Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private boolean truth(Object value) {
        return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value));
    }

    private String safeSummary(String answer, A2aTaskState state) {
        String value = answer == null || answer.isBlank() ? state.name() : answer;
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 1000 ? normalized : normalized.substring(0, 1000);
    }

    private String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private void putIfPresent(Map<String, Object> target, String key, String value) {
        if (StringUtils.hasText(value)) {
            target.put(key, value.trim());
        }
    }

    private String identifier(String value, String field) {
        if (!StringUtils.hasText(value) || value.trim().length() > 128) {
            throw permanent("A2A_RUNTIME_IDENTIFIER_INVALID",
                    field + " is required and must be at most 128 characters", null);
        }
        return value.trim();
    }

    private void requireCommand(DispatchCommand command) {
        if (command == null || command.agentConfigVersionId() <= 0
                || command.timeoutMs() <= 0 || command.canonicalMessage() == null) {
            throw permanent("A2A_RUNTIME_COMMAND_INVALID",
                    "A2A Runtime dispatch command is invalid", null);
        }
        identifier(command.executionId(), "executionId");
        identifier(command.taskId(), "taskId");
        identifier(command.contextId(), "contextId");
        identifier(command.agentId(), "agentId");
        identifier(command.principalKey(), "principalKey");
    }

    private Duration boundedExecuteTimeout(long timeoutMs) {
        long withResponseGrace = timeoutMs > Long.MAX_VALUE - 30_000L
                ? Long.MAX_VALUE : timeoutMs + 30_000L;
        return Duration.ofMillis(Math.max(30_000L, Math.min(withResponseGrace, 10 * 60_000L)));
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

    private String opaque(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private CallException permanent(String code, String message, Throwable cause) {
        return call(FailureKind.PERMANENT, code, message, cause);
    }

    private CallException call(FailureKind kind, String code, String message, Throwable cause) {
        return new CallException(kind, code,
                safeSummary(message, A2aTaskState.TASK_STATE_FAILED), cause);
    }
}
