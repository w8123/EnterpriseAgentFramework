package com.enterprise.ai.control.knowledge;

import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.control.internalauth.InternalServiceAuthSigner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** Calls Capability, the sole owner of project registry credentials, over signed internal HMAC. */
@Component
public class CapabilityProjectRequestVerificationGateway {

    static final String VERIFY_PATH = "/internal/capability/registry/project-requests/verify";
    private static final int MAX_RESPONSE_BYTES = 65_536;

    private final InternalServiceAuthSigner signer;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String capabilityServiceUrl;

    @Autowired
    public CapabilityProjectRequestVerificationGateway(
            InternalServiceAuthSigner signer,
            ObjectMapper objectMapper,
            @Value("${services.capability-service.url:http://localhost:18605}") String capabilityServiceUrl) {
        this(signer, objectMapper,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                capabilityServiceUrl);
    }

    CapabilityProjectRequestVerificationGateway(InternalServiceAuthSigner signer,
                                                ObjectMapper objectMapper,
                                                HttpClient httpClient,
                                                String capabilityServiceUrl) {
        this.signer = signer;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.capabilityServiceUrl = normalizeBaseUrl(capabilityServiceUrl);
    }

    VerifiedProject verify(ProjectRequest request) {
        validate(request);
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("projectCode", request.projectCode());
            body.put("appKey", request.appKey());
            body.put("method", request.method());
            body.put("path", request.path());
            body.put("timestamp", request.timestamp());
            body.put("nonce", request.nonce());
            body.put("bodySha256", request.bodySha256());
            body.put("signature", request.signature());
            byte[] payload = objectMapper.writeValueAsBytes(body);
            Map<String, String> signed = signer.sign(
                    "POST",
                    VERIFY_PATH,
                    InternalServiceAuthHeaders.IDENTITY_SOURCE_PROJECT_CREDENTIAL_VERIFICATION,
                    request.projectCode(),
                    request.appKey(),
                    payload);
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(capabilityServiceUrl + VERIFY_PATH))
                    .timeout(Duration.ofSeconds(10))
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(payload));
            signed.forEach(builder::header);
            HttpResponse<InputStream> response = httpClient.send(
                    builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            byte[] responseBody;
            try (InputStream input = response.body()) {
                responseBody = input == null ? new byte[0] : input.readNBytes(MAX_RESPONSE_BYTES + 1);
            }
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                throw unauthorized();
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300
                    || responseBody.length > MAX_RESPONSE_BYTES) {
                throw unavailable(null);
            }
            JsonNode result = objectMapper.readTree(responseBody);
            String verifiedProject = result == null ? null : result.path("projectCode").asText(null);
            long credentialId = result == null ? 0L : result.path("credentialId").asLong(0L);
            if (result == null || !result.path("verified").asBoolean(false)
                    || !request.projectCode().equals(verifiedProject) || credentialId < 1) {
                throw unauthorized();
            }
            return new VerifiedProject(
                    result.path("projectId").isIntegralNumber()
                            ? result.path("projectId").asLong() : null,
                    verifiedProject,
                    credentialId,
                    result.path("appKeyHash").asText(""));
        } catch (ResponseStatusException expected) {
            throw expected;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw unavailable(interrupted);
        } catch (IOException | RuntimeException failure) {
            throw unavailable(failure);
        }
    }

    private static void validate(ProjectRequest request) {
        if (request == null || blank(request.projectCode()) || blank(request.appKey())
                || blank(request.method()) || blank(request.path()) || blank(request.timestamp())
                || blank(request.nonce()) || blank(request.bodySha256()) || blank(request.signature())) {
            throw unauthorized();
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static ResponseStatusException unauthorized() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "project request authentication failed");
    }

    private static ResponseStatusException unavailable(Throwable cause) {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "project credential verification service is unavailable", cause);
    }

    private static String normalizeBaseUrl(String value) {
        String normalized = value == null || value.isBlank()
                ? "http://localhost:18605" : value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    record ProjectRequest(
            String projectCode,
            String appKey,
            String method,
            String path,
            String timestamp,
            String nonce,
            String bodySha256,
            String signature
    ) {
    }

    record VerifiedProject(
            Long projectId,
            String projectCode,
            long credentialId,
            String appKeyHash
    ) {
        String internalActorId() {
            return "credential:" + credentialId;
        }
    }
}
