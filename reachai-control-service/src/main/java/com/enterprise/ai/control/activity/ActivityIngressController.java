package com.enterprise.ai.control.activity;

import com.enterprise.ai.common.internalauth.InternalServiceHmac;
import com.enterprise.ai.common.internalauth.ProjectRequestAuthHeaders;
import com.enterprise.ai.control.knowledge.CapabilityProjectRequestVerificationGateway;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;

/**
 * Business systems report their employees' operations and searches here (Java starter, PHP SDK…).
 * Same exact-body project signature as business-index sync; the project comes only from the verified credential.
 */
@RestController
public class ActivityIngressController {

    public static final String PATH = "/api/activity-ingress/projects/{projectCode}/events";

    private final CapabilityProjectRequestVerificationGateway credentialGateway;
    private final ActivityService service;
    private final ObjectMapper objectMapper;
    private final int maxBodyBytes;

    public ActivityIngressController(CapabilityProjectRequestVerificationGateway credentialGateway,
                                     ActivityService service,
                                     ObjectMapper objectMapper,
                                     @Value("${reachai.activity.ingress.max-body-bytes:1048576}") int maxBodyBytes) {
        this.credentialGateway = credentialGateway;
        this.service = service;
        this.objectMapper = objectMapper;
        this.maxBodyBytes = Math.max(1, maxBodyBytes);
    }

    @PostMapping(value = PATH, consumes = MediaType.APPLICATION_JSON_VALUE)
    public ActivityService.IngestResult ingest(
            HttpServletRequest servletRequest,
            @PathVariable String projectCode,
            @RequestHeader(ProjectRequestAuthHeaders.APP_KEY) String appKey,
            @RequestHeader(ProjectRequestAuthHeaders.TIMESTAMP) String timestamp,
            @RequestHeader(ProjectRequestAuthHeaders.NONCE) String nonce,
            @RequestHeader(ProjectRequestAuthHeaders.BODY_SHA256) String bodySha256,
            @RequestHeader(ProjectRequestAuthHeaders.SIGNATURE) String signature,
            @RequestBody byte[] body) {
        String project = normalizeProjectCode(projectCode);
        byte[] payload = body == null ? new byte[0] : body;
        if (payload.length > maxBodyBytes) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "一次上报的内容太多，请分批上报");
        }
        if (servletRequest == null || StringUtils.hasText(servletRequest.getQueryString())) {
            throw unauthorized();
        }
        String actualDigest = InternalServiceHmac.bodySha256Hex(payload);
        if (!InternalServiceHmac.digestEqualsConstantTime(actualDigest, bodySha256)) {
            throw unauthorized();
        }
        credentialGateway.verify(new CapabilityProjectRequestVerificationGateway.ProjectRequest(
                project,
                required(appKey, 128),
                "POST",
                servletRequest.getRequestURI(),
                required(timestamp, 20),
                required(nonce, 128),
                actualDigest,
                required(signature, 128)));
        JsonNode json;
        try {
            json = objectMapper.readTree(payload);
        } catch (Exception invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "上报内容不是有效的 JSON");
        }
        return service.ingest(project, json);
    }

    static String normalizeProjectCode(String value) {
        if (!StringUtils.hasText(value)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "projectCode is invalid");
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9][a-z0-9_-]{0,95}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "projectCode is invalid");
        }
        return normalized;
    }

    private static String required(String value, int maxLength) {
        if (!StringUtils.hasText(value)) throw unauthorized();
        String normalized = value.trim();
        if (normalized.length() > maxLength || normalized.chars().anyMatch(Character::isISOControl)) {
            throw unauthorized();
        }
        return normalized;
    }

    private static ResponseStatusException unauthorized() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "project request authentication failed");
    }
}
