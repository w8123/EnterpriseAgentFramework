package com.enterprise.ai.control.managed;

import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.common.internalauth.InternalServiceHmac;
import com.enterprise.ai.control.internalauth.InternalServiceAuthSigner;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** Exact-byte HMAC client for Control-managed Runtime execution APIs. */
@Component
public class ControlManagedExecutionRuntimeClient {

    public static final String ROOT = "/internal/runtime/managed-executions";
    private static final int MAX_JSON_RESPONSE_BYTES = 1024 * 1024;
    private static final int MAX_ARTIFACT_RESPONSE_BYTES = 64 * 1024 * 1024;

    private final InternalServiceAuthSigner signer;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String runtimeServiceUrl;

    @Autowired
    public ControlManagedExecutionRuntimeClient(
            InternalServiceAuthSigner signer,
            ObjectMapper objectMapper,
            @Value("${services.runtime-service.url:http://localhost:18604}")
            String runtimeServiceUrl) {
        this(signer, objectMapper, runtimeServiceUrl, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    ControlManagedExecutionRuntimeClient(
            InternalServiceAuthSigner signer,
            ObjectMapper objectMapper,
            String runtimeServiceUrl,
            HttpClient httpClient) {
        this.signer = signer;
        this.objectMapper = objectMapper.copy()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.runtimeServiceUrl = normalizeBase(runtimeServiceUrl);
        this.httpClient = httpClient;
    }

    public CreatedView create(
            CreateRequest request,
            String tenantId,
            String userId) {
        return jsonExchange("POST", ROOT, request, tenantId, userId, CreatedView.class);
    }

    public ExecutionView get(String executionId, String tenantId, String userId) {
        String path = ROOT + "/" + segment(executionId);
        return jsonExchange("GET", path, null, tenantId, userId, ExecutionView.class);
    }

    public ExecutionView cancel(
            String executionId,
            String reason,
            String tenantId,
            String userId) {
        String path = ROOT + "/" + segment(executionId) + ":cancel";
        return jsonExchange("POST", path, new CancelRequest(reason),
                tenantId, userId, ExecutionView.class);
    }

    public ApprovalDecisionView resolveApproval(
            String executionId,
            String interactionId,
            ApprovalDecisionRequest request,
            String tenantId,
            String userId) {
        String path = ROOT + "/" + segment(executionId)
                + "/approvals/" + segment(interactionId) + ":resolve";
        return jsonExchange("POST", path, request, tenantId, userId,
                ApprovalDecisionView.class);
    }

    public List<ArtifactView> artifacts(
            String executionId,
            String tenantId,
            String userId) {
        String path = ROOT + "/" + segment(executionId) + "/artifacts";
        ArtifactView[] values = jsonExchange(
                "GET", path, null, tenantId, userId, ArtifactView[].class);
        return values == null ? List.of() : List.of(values);
    }

    public ApprovalView approval(
            String executionId,
            String tenantId,
            String userId) {
        String path = ROOT + "/" + segment(executionId) + "/approval";
        RawResponse response = exchange("GET", path, new byte[0], tenantId, userId,
                MAX_JSON_RESPONSE_BYTES, true);
        if (response.status() == 204 || response.body().length == 0) return null;
        try {
            return objectMapper.readValue(response.body(), ApprovalView.class);
        } catch (IOException failure) {
            throw new GatewayException(502, "MANAGED_RUNTIME_RESPONSE_INVALID",
                    "Runtime returned an invalid Managed Execution approval", failure);
        }
    }

    public ArtifactContent artifact(
            String executionId,
            String artifactId,
            String tenantId,
            String userId) {
        String path = ROOT + "/" + segment(executionId)
                + "/artifacts/" + segment(artifactId);
        RawResponse response = exchange("GET", path, new byte[0], tenantId, userId,
                MAX_ARTIFACT_RESPONSE_BYTES);
        String declaredDigest = response.headers().firstValue(
                "X-ReachAI-Artifact-SHA256").orElse("");
        String actualDigest = InternalServiceHmac.bodySha256Hex(response.body());
        if (!declaredDigest.matches("[a-f0-9]{64}")
                || !InternalServiceHmac.digestEqualsConstantTime(declaredDigest, actualDigest)) {
            throw new GatewayException(502, "MANAGED_ARTIFACT_INTEGRITY_FAILED",
                    "Runtime returned an invalid Managed Executor artifact");
        }
        String mediaType = response.headers().firstValue(HttpHeaders.CONTENT_TYPE)
                .orElse("application/octet-stream");
        String filename = "managed-execution-artifact.bin";
        try {
            String disposition = response.headers().firstValue(HttpHeaders.CONTENT_DISPOSITION)
                    .orElse("");
            if (StringUtils.hasText(disposition)) {
                String parsed = ContentDisposition.parse(disposition).getFilename();
                if (StringUtils.hasText(parsed)) filename = safeFilename(parsed);
            }
        } catch (IllegalArgumentException ignored) {
            // Keep the fixed fallback; never reflect malformed upstream headers.
        }
        return new ArtifactContent(filename, mediaType, actualDigest, response.body());
    }

    private <T> T jsonExchange(
            String method,
            String path,
            Object body,
            String tenantId,
            String userId,
            Class<T> responseType) {
        byte[] exactBody = write(body);
        RawResponse response = exchange(method, path, exactBody, tenantId, userId,
                MAX_JSON_RESPONSE_BYTES);
        try {
            return objectMapper.readValue(response.body(), responseType);
        } catch (IOException failure) {
            throw new GatewayException(502, "MANAGED_RUNTIME_RESPONSE_INVALID",
                    "Runtime returned an invalid Managed Execution response", failure);
        }
    }

    private RawResponse exchange(
            String method,
            String path,
            byte[] exactBody,
            String tenantId,
            String userId,
            int maximumBytes) {
        return exchange(method, path, exactBody, tenantId, userId, maximumBytes, false);
    }

    private RawResponse exchange(
            String method,
            String path,
            byte[] exactBody,
            String tenantId,
            String userId,
            int maximumBytes,
            boolean allowNoContent) {
        String trustedTenant = identifier(tenantId, "tenantId", 96);
        String trustedUser = identifier(userId, "userId", 128);
        Map<String, String> signed = signer.sign(
                method,
                path,
                InternalServiceAuthHeaders.IDENTITY_SOURCE_PLATFORM_SESSION,
                trustedTenant,
                trustedUser,
                exactBody);
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(runtimeServiceUrl + path))
                    .timeout(Duration.ofSeconds(60))
                    .header("Accept", "*/*")
                    .header("Accept-Encoding", "identity");
            signed.forEach(builder::header);
            if (exactBody.length > 0) {
                builder.header(HttpHeaders.CONTENT_TYPE, "application/json");
            }
            HttpRequest request = switch (method) {
                case "GET" -> builder.GET().build();
                case "POST" -> builder.POST(
                        HttpRequest.BodyPublishers.ofByteArray(exactBody)).build();
                default -> throw new IllegalArgumentException("unsupported HTTP method");
            };
            HttpResponse<InputStream> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofInputStream());
            long declaredLength = response.headers().firstValueAsLong("Content-Length")
                    .orElse(-1L);
            if (declaredLength > maximumBytes) {
                closeQuietly(response.body());
                throw new GatewayException(502, "MANAGED_RUNTIME_RESPONSE_TOO_LARGE",
                        "Runtime Managed Execution response exceeded the safety limit");
            }
            byte[] bytes;
            try (InputStream stream = response.body()) {
                bytes = stream == null ? new byte[0] : stream.readNBytes(maximumBytes + 1);
            }
            if (bytes.length > maximumBytes) {
                throw new GatewayException(502, "MANAGED_RUNTIME_RESPONSE_TOO_LARGE",
                        "Runtime Managed Execution response exceeded the safety limit");
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw problem(response.statusCode(), bytes);
            }
            if (!allowNoContent && response.statusCode() == 204) {
                throw new GatewayException(502, "MANAGED_RUNTIME_RESPONSE_INVALID",
                        "Runtime returned an empty Managed Execution response");
            }
            return new RawResponse(response.statusCode(), response.headers(), bytes);
        } catch (GatewayException known) {
            throw known;
        } catch (HttpTimeoutException | ConnectException uncertain) {
            throw new GatewayException(503, "MANAGED_RUNTIME_OUTCOME_UNCERTAIN",
                    "Runtime Managed Execution outcome is uncertain; retry is safe", uncertain);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new GatewayException(503, "MANAGED_RUNTIME_CALL_INTERRUPTED",
                    "Runtime Managed Execution call was interrupted", interrupted);
        } catch (Exception failure) {
            throw new GatewayException(503, "MANAGED_RUNTIME_UNAVAILABLE",
                    "Runtime Managed Execution service is unavailable", failure);
        }
    }

    private GatewayException problem(int status, byte[] body) {
        String code = "MANAGED_RUNTIME_HTTP_" + status;
        try {
            JsonNode parsed = objectMapper.readTree(body);
            String declared = parsed == null ? null : parsed.path("code").asText(null);
            if (declared != null && declared.matches("[A-Z0-9_]{3,96}")) code = declared;
        } catch (Exception ignored) {
            // Never reflect or log Runtime error bodies.
        }
        int outwardStatus = status >= 400 && status < 500 ? status : 503;
        return new GatewayException(outwardStatus, code,
                "Runtime rejected the Managed Execution request with HTTP " + status);
    }

    private byte[] write(Object value) {
        if (value == null) return new byte[0];
        try {
            return objectMapper.writeValueAsBytes(value);
        } catch (Exception failure) {
            throw new GatewayException(400, "MANAGED_RUNTIME_REQUEST_INVALID",
                    "Managed Execution request could not be serialized", failure);
        }
    }

    private String segment(String value) {
        return UriUtils.encodePathSegment(
                identifier(value, "path identifier", 160), StandardCharsets.UTF_8);
    }

    private String identifier(String value, String field, int maximum) {
        if (!StringUtils.hasText(value) || value.length() > maximum
                || !value.matches("[A-Za-z0-9._:-]+")) {
            throw new GatewayException(400, "MANAGED_RUNTIME_REQUEST_INVALID",
                    field + " is invalid");
        }
        return value.trim();
    }

    private String safeFilename(String value) {
        String normalized = value.replaceAll("[\\r\\n\\\\/]", "_");
        return normalized.length() > 160 ? normalized.substring(0, 160) : normalized;
    }

    private void closeQuietly(InputStream input) {
        if (input == null) return;
        try {
            input.close();
        } catch (IOException ignored) {
            // Preserve the size error.
        }
    }

    private String normalizeBase(String value) {
        String normalized = value == null ? "" : value.trim();
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            throw new IllegalArgumentException("services.runtime-service.url must be HTTP(S)");
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private record RawResponse(int status, java.net.http.HttpHeaders headers, byte[] body) {
    }

    public record CreateRequest(
            String projectCode,
            String sourceType,
            String sourceRef,
            String executorProvider,
            String sandboxProfile,
            String modelRef,
            String acceptanceProfile,
            String objective,
            Integer priority,
            Integer maxWallTimeSeconds,
            Integer approvalTimeoutSeconds) {
    }

    public record CreatedView(ExecutionView execution) {
    }

    public record ExecutionView(
            String executionId,
            String tenantId,
            String projectCode,
            String requestedByUserId,
            String sourceType,
            String sourceRef,
            String executorProvider,
            String sandboxProfile,
            String modelRef,
            String acceptanceProfile,
            String objectiveSha256,
            String status,
            String cleanupStatus,
            String pendingInteractionId,
            String pendingApprovalRequestId,
            int approvalCount,
            int priority,
            int maxWallTimeSeconds,
            int approvalTimeoutSeconds,
            int lastEventSequence,
            boolean cancelRequested,
            String errorCode,
            String errorMessage,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            LocalDateTime startedAt,
            LocalDateTime finalizingAt,
            LocalDateTime completedAt) {
    }

    public record ArtifactView(
            String schema,
            String executionId,
            String artifactId,
            String artifactType,
            String sha256,
            long sizeBytes,
            String mediaType,
            String validationStatus,
            String scanStatus,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            LocalDateTime retentionExpiresAt) {
    }

    public record CancelRequest(String reason) {
    }

    public record ApprovalDecisionRequest(String decision, String idempotencyKey) {
    }

    public record ApprovalDecisionView(
            String executionId,
            String interactionId,
            String approvalRequestId,
            String decision,
            long commandSequence,
            boolean expired,
            boolean idempotentReplay) {
    }

    public record ApprovalView(
            String schema,
            String executionId,
            String interactionId,
            String approvalRequestId,
            String status,
            JsonNode uiRequest,
            LocalDateTime expiresAt,
            LocalDateTime updatedAt) {
    }

    public record ArtifactContent(
            String filename,
            String mediaType,
            String sha256,
            byte[] bytes) {

        public InputStream inputStream() {
            return new ByteArrayInputStream(bytes);
        }
    }

    public static final class GatewayException extends RuntimeException {
        private final int status;
        private final String code;

        public GatewayException(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }

        public GatewayException(int status, String code, String message, Throwable cause) {
            super(message, cause);
            this.status = status;
            this.code = code;
        }

        public int status() {
            return status;
        }

        public String code() {
            return code;
        }
    }
}
