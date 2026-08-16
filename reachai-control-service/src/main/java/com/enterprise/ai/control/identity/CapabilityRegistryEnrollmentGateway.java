package com.enterprise.ai.control.identity;

import com.enterprise.ai.control.internalauth.InternalServiceAuthSigner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

/** Control-to-Capability adapter for issuing a one-time registry enrollment token. */
@Component
public class CapabilityRegistryEnrollmentGateway {

    static final String PATH = "/internal/capability/registry/enrollments";

    private final RestTemplate restTemplate;
    private final String capabilityServiceUrl;
    private final ObjectMapper objectMapper;
    private final InternalServiceAuthSigner signer;

    @Autowired
    public CapabilityRegistryEnrollmentGateway(RestTemplateBuilder builder,
                                               @Value("${services.capability-service.url:http://localhost:18605}")
                                               String capabilityServiceUrl,
                                               ObjectMapper objectMapper,
                                               InternalServiceAuthSigner signer) {
        this(builder.build(), capabilityServiceUrl, objectMapper, signer);
    }

    CapabilityRegistryEnrollmentGateway(RestTemplate restTemplate,
                                        String capabilityServiceUrl,
                                        ObjectMapper objectMapper,
                                        InternalServiceAuthSigner signer) {
        this.restTemplate = restTemplate;
        this.capabilityServiceUrl = normalizeBaseUrl(capabilityServiceUrl);
        this.objectMapper = objectMapper;
        this.signer = signer;
    }

    public IssuedEnrollment issue(PlatformAuthenticatedSession session, String projectCode) {
        if (session == null || session.user() == null || session.user().getId() == null) {
            throw new IllegalArgumentException("a live platform session is required");
        }
        if (!signer.secretConfigured()) {
            throw new IllegalStateException("REACHAI_INTERNAL_SERVICE_SECRET is required before Enrollment Tokens can be issued");
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("projectCode", projectCode == null ? "" : projectCode.trim());
            byte[] bodyBytes = objectMapper.writeValueAsBytes(body);
            Map<String, String> signed = signer.sign(
                    "POST", PATH, "PLATFORM_SESSION", String.valueOf(session.user().getId()), bodyBytes);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));
            signed.forEach(headers::set);
            ResponseEntity<String> response = restTemplate.exchange(
                    capabilityServiceUrl + PATH, HttpMethod.POST, new HttpEntity<>(bodyBytes, headers), String.class);
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new IllegalStateException("Capability service rejected Enrollment Token issuance");
            }
            JsonNode root = objectMapper.readTree(response.getBody() == null ? "{}" : response.getBody());
            String token = text(root, "enrollmentToken");
            String normalizedProjectCode = text(root, "projectCode");
            String expiresAt = text(root, "expiresAt");
            if (!StringUtils.hasText(token) || !StringUtils.hasText(normalizedProjectCode)
                    || !StringUtils.hasText(expiresAt)) {
                throw new IllegalStateException("Capability service returned an invalid Enrollment Token response");
            }
            return new IssuedEnrollment(token, normalizedProjectCode, expiresAt, root.path("ttlHours").asInt(72));
        } catch (RestClientException unavailable) {
            throw new IllegalStateException("Capability service is unavailable for Enrollment Token issuance", unavailable);
        } catch (Exception failure) {
            throw failure instanceof IllegalStateException state ? state
                    : new IllegalStateException("Enrollment Token issuance failed", failure);
        }
    }

    private static String text(JsonNode root, String name) {
        return root != null && root.hasNonNull(name) ? root.path(name).asText().trim() : null;
    }

    private static String normalizeBaseUrl(String raw) {
        String value = raw == null ? "" : raw.trim();
        return StringUtils.hasText(value) ? value.replaceAll("/+$", "") : "http://localhost:18605";
    }

    public record IssuedEnrollment(String enrollmentToken, String projectCode, String expiresAt, int ttlHours) {
    }
}
