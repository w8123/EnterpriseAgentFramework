package com.enterprise.ai.control.knowledge;

import com.enterprise.ai.common.internalauth.InternalServiceHmac;
import com.enterprise.ai.common.internalauth.ProjectRequestAuthHeaders;
import com.enterprise.ai.control.identity.PlatformAuthAuditService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;

/** Body-bound project-credential ingress for automated structured business-index sync. */
@RestController
@RequestMapping("/api/knowledge-ingress/projects/{projectCode}/biz-index/{indexCode}")
public class KnowledgeProjectIngressController {

    private final CapabilityProjectRequestVerificationGateway credentialGateway;
    private final KnowledgeBizIndexGateway knowledgeGateway;
    private final PlatformAuthAuditService auditService;
    private final ObjectMapper objectMapper;
    private final int maxBodyBytes;

    public KnowledgeProjectIngressController(
            CapabilityProjectRequestVerificationGateway credentialGateway,
            KnowledgeBizIndexGateway knowledgeGateway,
            PlatformAuthAuditService auditService,
            ObjectMapper objectMapper,
            @Value("${reachai.knowledge.project-ingress.max-body-bytes:10485760}") int maxBodyBytes) {
        this.credentialGateway = credentialGateway;
        this.knowledgeGateway = knowledgeGateway;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.maxBodyBytes = Math.max(1, maxBodyBytes);
    }

    @PostMapping(value = "/upsert", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> upsert(
            HttpServletRequest servletRequest,
            @PathVariable String projectCode,
            @PathVariable String indexCode,
            @RequestHeader(ProjectRequestAuthHeaders.APP_KEY) String appKey,
            @RequestHeader(ProjectRequestAuthHeaders.TIMESTAMP) String timestamp,
            @RequestHeader(ProjectRequestAuthHeaders.NONCE) String nonce,
            @RequestHeader(ProjectRequestAuthHeaders.BODY_SHA256) String bodySha256,
            @RequestHeader(ProjectRequestAuthHeaders.SIGNATURE) String signature,
            @RequestBody byte[] body) {
        return mutate("POST", "upsert", servletRequest, projectCode, indexCode, null,
                appKey, timestamp, nonce, bodySha256, signature, body);
    }

    @PostMapping(value = "/batch", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> batch(
            HttpServletRequest servletRequest,
            @PathVariable String projectCode,
            @PathVariable String indexCode,
            @RequestHeader(ProjectRequestAuthHeaders.APP_KEY) String appKey,
            @RequestHeader(ProjectRequestAuthHeaders.TIMESTAMP) String timestamp,
            @RequestHeader(ProjectRequestAuthHeaders.NONCE) String nonce,
            @RequestHeader(ProjectRequestAuthHeaders.BODY_SHA256) String bodySha256,
            @RequestHeader(ProjectRequestAuthHeaders.SIGNATURE) String signature,
            @RequestBody byte[] body) {
        return mutate("POST", "batch", servletRequest, projectCode, indexCode, null,
                appKey, timestamp, nonce, bodySha256, signature, body);
    }

    @PostMapping(value = "/delete", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> deleteRecord(
            HttpServletRequest servletRequest,
            @PathVariable String projectCode,
            @PathVariable String indexCode,
            @RequestHeader(ProjectRequestAuthHeaders.APP_KEY) String appKey,
            @RequestHeader(ProjectRequestAuthHeaders.TIMESTAMP) String timestamp,
            @RequestHeader(ProjectRequestAuthHeaders.NONCE) String nonce,
            @RequestHeader(ProjectRequestAuthHeaders.BODY_SHA256) String bodySha256,
            @RequestHeader(ProjectRequestAuthHeaders.SIGNATURE) String signature,
            @RequestBody byte[] body) {
        return mutate("POST", "delete", servletRequest, projectCode, indexCode, bizId(body),
                appKey, timestamp, nonce, bodySha256, signature, body);
    }

    private ResponseEntity<byte[]> mutate(
            String method,
            String operation,
            HttpServletRequest servletRequest,
            String rawProjectCode,
            String rawIndexCode,
            String rawBizId,
            String appKey,
            String timestamp,
            String nonce,
            String bodySha256,
            String signature,
            byte[] body) {
        String projectCode = normalizeProjectCode(rawProjectCode);
        String indexCode = normalizeIndexCode(rawIndexCode);
        byte[] payload = body == null ? new byte[0] : body;
        if (payload.length > maxBodyBytes) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "project ingress request exceeds the configured limit");
        }
        if (servletRequest == null || StringUtils.hasText(servletRequest.getQueryString())) {
            throw unauthorized();
        }
        String requestPath = servletRequest.getRequestURI();
        String actualDigest = InternalServiceHmac.bodySha256Hex(payload);
        if (!InternalServiceHmac.digestEqualsConstantTime(actualDigest, bodySha256)) {
            throw unauthorized();
        }
        CapabilityProjectRequestVerificationGateway.VerifiedProject verified = credentialGateway.verify(
                new CapabilityProjectRequestVerificationGateway.ProjectRequest(
                        projectCode,
                        required(appKey, "appKey", 128),
                        method,
                        requestPath,
                        required(timestamp, "timestamp", 20),
                        required(nonce, "nonce", 128),
                        actualDigest,
                        required(signature, "signature", 128)));

        String internalPath = KnowledgeBizIndexGateway.PROJECT_INTERNAL_ROOT
                + "/" + encode(projectCode)
                + "/biz-index/" + encode(indexCode)
                + "/" + operation;
        String targetId = sha256(projectCode + "\n" + indexCode
                + (rawBizId == null ? "" : "\n" + required(rawBizId, "bizId", 256)));
        auditService.record(null,
                "KNOWLEDGE_PROJECT_INGRESS_" + operation.toUpperCase(Locale.ROOT) + "_REQUESTED",
                "KNOWLEDGE_BUSINESS_INDEX",
                targetId,
                Map.of("projectCode", projectCode,
                        "indexCode", indexCode,
                        "credentialId", verified.credentialId()));
        KnowledgeBizIndexGateway.GatewayResponse response = knowledgeGateway.exchangeProject(
                method,
                internalPath,
                projectCode,
                verified.internalActorId(),
                payload,
                payload.length == 0 ? null : MediaType.APPLICATION_JSON_VALUE);
        return respond(response);
    }

    private static ResponseEntity<byte[]> respond(KnowledgeBizIndexGateway.GatewayResponse response) {
        MediaType mediaType;
        try {
            mediaType = MediaType.parseMediaType(response.contentType());
        } catch (Exception invalid) {
            mediaType = MediaType.APPLICATION_JSON;
        }
        return ResponseEntity.status(response.status())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(mediaType)
                .body(response.body());
    }

    private String bizId(byte[] body) {
        if (body == null || body.length > maxBodyBytes) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "project ingress request exceeds the configured limit");
        }
        try {
            return required(objectMapper.readTree(body).path("bizId").asText(null), "bizId", 256);
        } catch (ResponseStatusException expected) {
            throw expected;
        } catch (Exception invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "delete request must contain a valid bizId", invalid);
        }
    }

    private static String normalizeProjectCode(String value) {
        String normalized = required(value, "projectCode", 96).toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9][a-z0-9_-]{0,95}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "projectCode is invalid");
        }
        return normalized;
    }

    private static String normalizeIndexCode(String value) {
        String normalized = required(value, "indexCode", 63);
        if (!normalized.matches("[A-Za-z][A-Za-z0-9_]{1,62}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "indexCode is invalid");
        }
        return normalized;
    }

    private static String required(String value, String field, int maxLength) {
        if (!StringUtils.hasText(value)) {
            throw unauthorized();
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength || normalized.chars().anyMatch(Character::isISOControl)) {
            throw unauthorized();
        }
        return normalized;
    }

    private static String encode(String value) {
        return UriUtils.encodePathSegment(value, StandardCharsets.UTF_8);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static ResponseStatusException unauthorized() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "project request authentication failed");
    }
}
