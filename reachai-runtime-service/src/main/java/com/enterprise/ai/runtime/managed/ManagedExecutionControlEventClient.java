package com.enterprise.ai.runtime.managed;

import com.enterprise.ai.runtime.client.control.RuntimeControlInternalAuthSigner;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.beans.factory.annotation.Autowired;
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

/** Exact-byte, HMAC-authenticated and safely retryable Runtime-to-Control event delivery. */
@Component
public class ManagedExecutionControlEventClient {

    public static final String EVENTS_PATH = "/internal/control/managed-executions/events";
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;

    private final RuntimeControlInternalAuthSigner signer;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String controlServiceUrl;

    @Autowired
    public ManagedExecutionControlEventClient(
            RuntimeControlInternalAuthSigner signer,
            ObjectMapper objectMapper,
            @Value("${services.control-service.url:http://localhost:18603}") String controlServiceUrl) {
        this(signer, objectMapper, controlServiceUrl, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    ManagedExecutionControlEventClient(
            RuntimeControlInternalAuthSigner signer,
            ObjectMapper objectMapper,
            String controlServiceUrl,
            HttpClient httpClient) {
        this.signer = signer;
        this.objectMapper = objectMapper.copy()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        this.controlServiceUrl = normalizeBase(controlServiceUrl);
        this.httpClient = httpClient;
    }

    public PublishResponse publish(PublishRequest request) {
        byte[] exactBody = write(request);
        Map<String, String> headers = signer.sign("POST", EVENTS_PATH, exactBody);
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(controlServiceUrl + EVENTS_PATH))
                    .timeout(Duration.ofSeconds(30))
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
                throw new DeliveryException("MANAGED_CONTROL_RESPONSE_TOO_LARGE",
                        "Control Managed Execution response exceeded the Runtime safety limit");
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw problem(response.statusCode(), body);
            }
            PublishResponse result = objectMapper.readValue(body, PublishResponse.class);
            if (result == null || !request.eventId().equals(result.eventId()) || !result.accepted()) {
                throw new DeliveryException("MANAGED_CONTROL_RESPONSE_INVALID",
                        "Control returned an invalid Managed Execution acknowledgement");
            }
            return result;
        } catch (DeliveryException known) {
            throw known;
        } catch (HttpTimeoutException | ConnectException uncertain) {
            throw new DeliveryException("MANAGED_CONTROL_DELIVERY_UNCERTAIN",
                    "Managed Execution event delivery outcome is uncertain and will be retried", uncertain);
        } catch (Exception failure) {
            throw new DeliveryException("MANAGED_CONTROL_DELIVERY_FAILED",
                    "Managed Execution event delivery failed safely and will be retried", failure);
        }
    }

    private DeliveryException problem(int status, byte[] body) {
        String code = "MANAGED_CONTROL_HTTP_" + status;
        try {
            JsonNode parsed = objectMapper.readTree(body);
            String declared = parsed == null ? null : parsed.path("code").asText(null);
            if (declared != null && declared.matches("[A-Z0-9_]{3,96}")) code = declared;
        } catch (Exception ignored) {
            // Remote bodies are deliberately never reflected in errors or logs.
        }
        return new DeliveryException(code,
                "Control rejected a Managed Execution event with HTTP " + status);
    }

    private byte[] write(Object value) {
        try {
            return objectMapper.writeValueAsBytes(value);
        } catch (Exception failure) {
            throw new DeliveryException("MANAGED_CONTROL_REQUEST_INVALID",
                    "Runtime could not serialize a Managed Execution event", failure);
        }
    }

    private String normalizeBase(String value) {
        String normalized = value == null ? "" : value.trim();
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            throw new IllegalArgumentException("services.control-service.url must be HTTP(S)");
        }
        while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        return normalized;
    }

    public record PublishRequest(
            String schema,
            String eventId,
            String executionId,
            String eventType,
            JsonNode payload) {
    }

    public record PublishResponse(
            String schema,
            String eventId,
            boolean accepted,
            boolean idempotentReplay) {
    }

    public static final class DeliveryException extends RuntimeException {
        private final String code;

        public DeliveryException(String code, String message) {
            super(message);
            this.code = code;
        }

        public DeliveryException(String code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
