package com.enterprise.ai.control.capability;

import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.control.internalauth.InternalServiceAuthSigner;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriUtils;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.StringJoiner;

/** Exact-byte HMAC client for Control-managed Capability snapshot review. */
@Service
public class CapabilityReviewGateway {

    private static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(45);
    private static final String REGISTRY_ROOT = "/api/registry";
    private static final String TOOL_CATALOG_PATH = "/api/tools";
    private static final String BUSINESS_METHOD_CATALOG_PATH = "/internal/capability/business-methods";
    private static final String HTTP_API_CATALOG_PATH = "/internal/capability/http-apis";
    private static final String CAPABILITY_PROJECT_BY_ID_PATH = "/internal/capability/projects/by-id";

    private final InternalServiceAuthSigner signer;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String capabilityServiceUrl;

    @Autowired
    public CapabilityReviewGateway(
            InternalServiceAuthSigner signer,
            ObjectMapper objectMapper,
            @Value("${services.capability-service.url:http://localhost:18605}")
            String capabilityServiceUrl) {
        this(signer, objectMapper, capabilityServiceUrl,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    CapabilityReviewGateway(InternalServiceAuthSigner signer,
                            ObjectMapper objectMapper,
                            String capabilityServiceUrl,
                            HttpClient httpClient) {
        this.signer = signer;
        this.objectMapper = objectMapper;
        this.capabilityServiceUrl = normalizeBaseUrl(capabilityServiceUrl);
        this.httpClient = httpClient;
    }

    public ResponseEntity<Object> createPendingSnapshot(String projectCode,
                                                        Map<String, Object> body,
                                                        String actorId) {
        // Console synchronization is an intake action. It must use Capability's
        // pending-only diff boundary instead of the SDK sync identity.
        return exchange("POST", projectCapabilitiesPath(projectCode) + "/diff", body, actorId);
    }

    public ResponseEntity<Object> listSnapshots(String projectCode, String actorId) {
        return exchange("GET", projectSnapshotsPath(projectCode), null, actorId);
    }

    public ResponseEntity<Object> getCapability(String name, String actorId) {
        return exchange("GET", TOOL_CATALOG_PATH + "/" + segment(name), null, actorId);
    }

    public ResponseEntity<Object> getBusinessMethod(String name, String actorId) {
        return exchange("GET", BUSINESS_METHOD_CATALOG_PATH + "/" + segment(name), null, actorId);
    }

    /** Owner-provided execution snapshot; this never returns a target URL or project credential. */
    public ResponseEntity<Object> getBusinessMethodInvocationContext(String name, String actorId) {
        return exchange("GET", BUSINESS_METHOD_CATALOG_PATH + "/" + segment(name) + "/invocation-context",
                null, actorId);
    }

    public ResponseEntity<Object> listCapabilities(int current,
                                                    int size,
                                                    String keyword,
                                                    String source,
                                                    Boolean enabled,
                                                    Long projectId,
                                                    String actorId) {
        StringJoiner query = new StringJoiner("&")
                .add("current=" + Math.max(1, current))
                .add("size=" + Math.min(100, Math.max(1, size)));
        addQueryParam(query, "keyword", keyword);
        addQueryParam(query, "source", source);
        if (enabled != null) {
            query.add("enabled=" + enabled);
        }
        if (projectId != null) {
            query.add("projectId=" + positiveId(projectId, "projectId"));
        }
        return exchange("GET", TOOL_CATALOG_PATH, null, actorId, query.toString());
    }

    public ResponseEntity<Object> listBusinessMethods(int current,
                                                       int size,
                                                       String keyword,
                                                       Boolean enabled,
                                                       Long projectId,
                                                       String actorId) {
        StringJoiner query = new StringJoiner("&")
                .add("current=" + Math.max(1, current))
                .add("size=" + Math.min(100, Math.max(1, size)));
        addQueryParam(query, "keyword", keyword);
        if (enabled != null) {
            query.add("enabled=" + enabled);
        }
        if (projectId != null) {
            query.add("projectId=" + positiveId(projectId, "projectId"));
        }
        return exchange("GET", BUSINESS_METHOD_CATALOG_PATH, null, actorId, query.toString());
    }

    public ResponseEntity<Object> getProjectById(Long projectId, String actorId) {
        return exchange("GET", CAPABILITY_PROJECT_BY_ID_PATH + "/"
                + positiveId(projectId, "projectId"), null, actorId);
    }

    public ResponseEntity<Object> listHttpApis(long projectId, String environment, String keyword,
                                               String method, String sourceStatus, int current, int size,
                                               String actorId) {
        StringJoiner query = new StringJoiner("&")
                .add("projectId=" + positiveId(projectId, "projectId"))
                .add("current=" + Math.max(1, current))
                .add("size=" + Math.min(100, Math.max(1, size)));
        addQueryParam(query, "environment", environment);
        addQueryParam(query, "keyword", keyword);
        addQueryParam(query, "method", method);
        addQueryParam(query, "sourceStatus", sourceStatus);
        return exchange("GET", HTTP_API_CATALOG_PATH, null, actorId, query.toString());
    }

    public ResponseEntity<Object> getHttpApi(long id, String actorId) {
        return exchange("GET", HTTP_API_CATALOG_PATH + "/" + positiveId(id, "apiId"), null, actorId);
    }

    public ResponseEntity<Object> readApiMarket(String relativePath, Map<String, String> parameters, String actorId) {
        StringJoiner query = new StringJoiner("&");
        if (parameters != null) parameters.forEach((key, value) -> addQueryParam(query, key, value));
        return exchange("GET", "/internal/capability/api-market" + relativePath, null, actorId, query.toString());
    }

    public ResponseEntity<Object> selectApiMarket(String entryKey, Map<String, Object> body, String actorId) {
        return exchange("POST", "/internal/capability/api-market/entries/" + segment(entryKey) + "/integrations", body, actorId);
    }

    public ResponseEntity<Object> updateApiMarketStatus(long integrationId, Map<String, Object> body, String actorId) {
        return exchange("PUT", "/internal/capability/api-market/integrations/" + positiveId(integrationId, "integrationId") + "/status", body, actorId);
    }

    public ResponseEntity<Object> acceptHttpApi(long id, String expectedSourceSetRevision, String actorId) {
        return exchange("POST", HTTP_API_CATALOG_PATH + "/" + positiveId(id, "apiId") + "/accept",
                Map.of("expectedSourceSetRevision", expectedSourceSetRevision), actorId);
    }

    public ResponseEntity<Object> listChanges(String projectCode, String state, String keyword,
                                               int current, int size, String actorId) {
        String path = REGISTRY_ROOT + "/projects/" + segment(projectCode) + "/capability-changes";
        String query = "state=" + org.springframework.web.util.UriUtils.encodeQueryParam(state, StandardCharsets.UTF_8)
                + "&keyword=" + org.springframework.web.util.UriUtils.encodeQueryParam(keyword, StandardCharsets.UTF_8)
                + "&current=" + Math.max(1, current) + "&size=" + Math.min(50, Math.max(1, size));
        return exchange("GET", path, null, actorId, query);
    }

    public ResponseEntity<Object> listDiffItems(String projectCode,
                                                Long snapshotId,
                                                String actorId) {
        return exchange("GET", projectSnapshotsPath(projectCode) + "/"
                + positiveId(snapshotId, "snapshotId") + "/diff-items", null, actorId);
    }

    public ResponseEntity<Object> reviewDiffItem(String projectCode,
                                                 Long diffItemId,
                                                 Map<String, Object> body,
                                                 String actorId) {
        return exchange("POST", projectDiffItemPath(projectCode, diffItemId) + "/review", body, actorId);
    }

    public ResponseEntity<Object> rollbackDiffItem(String projectCode,
                                                   Long diffItemId,
                                                   Map<String, Object> body,
                                                   String actorId) {
        return exchange("POST", projectDiffItemPath(projectCode, diffItemId) + "/rollback", body, actorId);
    }

    private ResponseEntity<Object> exchange(String method,
                                            String path,
                                            Object body,
                                            String actorId) {
        return exchange(method, path, body, actorId, null);
    }

    private ResponseEntity<Object> exchange(String method, String path, Object body, String actorId, String query) {
        byte[] payload = serialize(body);
        Map<String, String> signedHeaders = signer.sign(
                method,
                path,
                InternalServiceAuthHeaders.IDENTITY_SOURCE_PLATFORM_SESSION,
                requireActorId(actorId),
                payload);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(capabilityServiceUrl + path + (query == null ? "" : "?" + query)))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json");
        if (payload.length > 0) {
            builder.header("Content-Type", "application/json");
        }
        signedHeaders.forEach(builder::header);
        HttpRequest.BodyPublisher publisher = payload.length == 0
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(payload);
        try {
            HttpResponse<InputStream> response = httpClient.send(
                    builder.method(method, publisher).build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            byte[] responseBytes;
            try (InputStream input = response.body()) {
                responseBytes = input.readNBytes(MAX_RESPONSE_BYTES + 1);
            }
            if (responseBytes.length > MAX_RESPONSE_BYTES) {
                throw new IOException("Capability review response exceeded 8 MiB");
            }
            Object responseBody = responseBytes.length == 0
                    ? null
                    : objectMapper.readValue(responseBytes, Object.class);
            return ResponseEntity.status(response.statusCode()).body(responseBody);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return unavailable("CAPABILITY_REVIEW_INTERRUPTED");
        } catch (IOException unavailable) {
            return unavailable("CAPABILITY_REVIEW_SERVICE_UNAVAILABLE");
        }
    }

    private byte[] serialize(Object body) {
        if (body == null) {
            return new byte[0];
        }
        try {
            return objectMapper.writeValueAsBytes(body);
        } catch (IOException invalid) {
            throw new IllegalArgumentException("Capability review request cannot be serialized", invalid);
        }
    }

    private ResponseEntity<Object> unavailable(String code) {
        return ResponseEntity.status(503).body(Map.of(
                "success", false,
                "code", code,
                "message", "ReachAI Capability Service is unavailable"));
    }

    private static String projectCapabilitiesPath(String projectCode) {
        return REGISTRY_ROOT + "/projects/" + segment(projectCode) + "/capabilities";
    }

    private static String projectSnapshotsPath(String projectCode) {
        return REGISTRY_ROOT + "/projects/" + segment(projectCode) + "/capability-snapshots";
    }

    private static String projectDiffItemPath(String projectCode, Long diffItemId) {
        return REGISTRY_ROOT + "/projects/" + segment(projectCode) + "/capability-diff-items/"
                + positiveId(diffItemId, "diffItemId");
    }

    private static void addQueryParam(StringJoiner query, String name, String value) {
        if (value != null) {
            query.add(name + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8)
                    .replace("+", "%20"));
        }
    }

    private static String segment(String value) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("projectCode is required");
        }
        return UriUtils.encodePathSegment(value.trim(), StandardCharsets.UTF_8);
    }

    private static String positiveId(Long value, String name) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return String.valueOf(value);
    }

    private static String requireActorId(String value) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("platform session userId is required");
        }
        return value.trim();
    }

    private static String normalizeBaseUrl(String value) {
        String normalized = StringUtils.hasText(value)
                ? value.trim()
                : "http://localhost:18605";
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            throw new IllegalArgumentException("services.capability-service.url must be HTTP(S)");
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
