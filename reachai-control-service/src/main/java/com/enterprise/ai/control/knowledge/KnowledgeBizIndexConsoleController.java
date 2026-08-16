package com.enterprise.ai.control.knowledge;

import com.enterprise.ai.control.identity.PlatformAuthAuditService;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformAuthorizationService;
import com.enterprise.ai.control.identity.PlatformConsoleAuthInterceptor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Platform-session and RBAC protected public BFF for Knowledge business indexes. */
@RestController
@RequestMapping("/api/knowledge/biz-index")
public class KnowledgeBizIndexConsoleController {

    static final String READ_PERMISSION = "platform:read";
    static final String WRITE_PERMISSION = "platform:write";

    private final KnowledgeBizIndexGateway gateway;
    private final PlatformAuthorizationService authorizationService;
    private final PlatformAuthAuditService auditService;
    private final ObjectMapper objectMapper;
    private final String consoleTenantId;

    public KnowledgeBizIndexConsoleController(
            KnowledgeBizIndexGateway gateway,
            PlatformAuthorizationService authorizationService,
            PlatformAuthAuditService auditService,
            ObjectMapper objectMapper,
            @Value("${reachai.knowledge.console-ingress.tenant-id:default}") String consoleTenantId) {
        this.gateway = gateway;
        this.authorizationService = authorizationService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.consoleTenantId = normalizeTenant(consoleTenantId);
    }

    @GetMapping("/list")
    public ResponseEntity<byte[]> list(HttpServletRequest request) {
        PlatformAuthenticatedSession actor = require(request, READ_PERMISSION);
        audit(actor, "KNOWLEDGE_BIZ_INDEX_LIST_REQUESTED", "collection", Map.of());
        return respond(gateway.exchange("GET", KnowledgeBizIndexGateway.INTERNAL_ROOT,
                consoleTenantId, actorId(actor), null, null));
    }

    @GetMapping("/{indexCode}")
    public ResponseEntity<byte[]> detail(HttpServletRequest request, @PathVariable String indexCode) {
        PlatformAuthenticatedSession actor = require(request, READ_PERMISSION);
        String code = normalizeIndexCode(indexCode);
        audit(actor, "KNOWLEDGE_BIZ_INDEX_READ_REQUESTED", code, Map.of("indexCode", code));
        return respond(gateway.exchange("GET", path(code), consoleTenantId, actorId(actor), null, null));
    }

    @GetMapping("/{indexCode}/stats")
    public ResponseEntity<byte[]> stats(HttpServletRequest request, @PathVariable String indexCode) {
        PlatformAuthenticatedSession actor = require(request, READ_PERMISSION);
        String code = normalizeIndexCode(indexCode);
        audit(actor, "KNOWLEDGE_BIZ_INDEX_STATS_REQUESTED", code, Map.of("indexCode", code));
        return respond(gateway.exchange("GET", path(code) + "/stats",
                consoleTenantId, actorId(actor), null, null));
    }

    @PostMapping
    public ResponseEntity<byte[]> create(HttpServletRequest request, @RequestBody JsonNode body) {
        PlatformAuthenticatedSession actor = require(request, WRITE_PERMISSION);
        byte[] payload = jsonBody(body);
        String code = normalizeIndexCode(body == null ? null : body.path("indexCode").asText(null));
        audit(actor, "KNOWLEDGE_BIZ_INDEX_CREATE_REQUESTED", code, Map.of("indexCode", code));
        return respond(gateway.exchange("POST", KnowledgeBizIndexGateway.INTERNAL_ROOT,
                consoleTenantId, actorId(actor), payload, MediaType.APPLICATION_JSON_VALUE));
    }

    @PutMapping("/{indexCode}")
    public ResponseEntity<byte[]> update(HttpServletRequest request,
                                         @PathVariable String indexCode,
                                         @RequestBody JsonNode body) {
        PlatformAuthenticatedSession actor = require(request, WRITE_PERMISSION);
        String code = normalizeIndexCode(indexCode);
        byte[] payload = jsonBody(body);
        audit(actor, "KNOWLEDGE_BIZ_INDEX_UPDATE_REQUESTED", code, Map.of("indexCode", code));
        return respond(gateway.exchange("PUT", path(code), consoleTenantId, actorId(actor),
                payload, MediaType.APPLICATION_JSON_VALUE));
    }

    @DeleteMapping("/{indexCode}")
    public ResponseEntity<byte[]> delete(HttpServletRequest request, @PathVariable String indexCode) {
        PlatformAuthenticatedSession actor = require(request, WRITE_PERMISSION);
        String code = normalizeIndexCode(indexCode);
        audit(actor, "KNOWLEDGE_BIZ_INDEX_DELETE_REQUESTED", code, Map.of("indexCode", code));
        return respond(gateway.exchange("DELETE", path(code), consoleTenantId, actorId(actor), null, null));
    }

    @PostMapping("/{indexCode}/upsert")
    public ResponseEntity<byte[]> upsert(
            HttpServletRequest request,
            @PathVariable String indexCode,
            @RequestPart("data") String dataJson,
            @RequestPart(value = "attachments", required = false) List<MultipartFile> attachments) {
        PlatformAuthenticatedSession actor = require(request, WRITE_PERMISSION);
        String code = normalizeIndexCode(indexCode);
        String recordHash = recordHash(code, dataJson);
        audit(actor, "KNOWLEDGE_BIZ_INDEX_UPSERT_REQUESTED", recordHash,
                Map.of("indexCode", code,
                        "attachmentCount", attachments == null ? 0 : attachments.size()));
        return respond(gateway.exchangeMultipart(path(code) + "/upsert",
                consoleTenantId, actorId(actor), dataJson, attachments));
    }

    @PostMapping("/{indexCode}/batch")
    public ResponseEntity<byte[]> batch(HttpServletRequest request,
                                        @PathVariable String indexCode,
                                        @RequestBody JsonNode body) {
        PlatformAuthenticatedSession actor = require(request, WRITE_PERMISSION);
        String code = normalizeIndexCode(indexCode);
        byte[] payload = jsonBody(body);
        int itemCount = body != null && body.path("items").isArray() ? body.path("items").size() : 0;
        audit(actor, "KNOWLEDGE_BIZ_INDEX_BATCH_UPSERT_REQUESTED", code,
                Map.of("indexCode", code, "itemCount", itemCount));
        return respond(gateway.exchange("POST", path(code) + "/batch",
                consoleTenantId, actorId(actor), payload, MediaType.APPLICATION_JSON_VALUE));
    }

    @DeleteMapping("/{indexCode}/record/{bizId}")
    public ResponseEntity<byte[]> deleteRecord(HttpServletRequest request,
                                                @PathVariable String indexCode,
                                                @PathVariable String bizId) {
        PlatformAuthenticatedSession actor = require(request, WRITE_PERMISSION);
        String code = normalizeIndexCode(indexCode);
        String record = requiredSegment(bizId, "bizId", 256);
        String targetHash = sha256(code + "\n" + record);
        audit(actor, "KNOWLEDGE_BIZ_INDEX_RECORD_DELETE_REQUESTED", targetHash,
                Map.of("indexCode", code));
        return respond(gateway.exchange("DELETE",
                path(code) + "/record/" + encode(record),
                consoleTenantId, actorId(actor), null, null));
    }

    @PostMapping("/{indexCode}/rebuild")
    public ResponseEntity<byte[]> rebuild(HttpServletRequest request, @PathVariable String indexCode) {
        PlatformAuthenticatedSession actor = require(request, WRITE_PERMISSION);
        String code = normalizeIndexCode(indexCode);
        audit(actor, "KNOWLEDGE_BIZ_INDEX_REBUILD_REQUESTED", code, Map.of("indexCode", code));
        return respond(gateway.exchange("POST", path(code) + "/rebuild",
                consoleTenantId, actorId(actor), null, null));
    }

    @PostMapping("/{indexCode}/search")
    public ResponseEntity<byte[]> search(HttpServletRequest request,
                                         @PathVariable String indexCode,
                                         @RequestBody JsonNode body) {
        PlatformAuthenticatedSession actor = require(request, READ_PERMISSION);
        String code = normalizeIndexCode(indexCode);
        byte[] payload = jsonBody(body);
        audit(actor, "KNOWLEDGE_BIZ_INDEX_SEARCH_REQUESTED", code,
                Map.of("indexCode", code));
        return respond(gateway.exchange("POST", path(code) + "/search",
                consoleTenantId, actorId(actor), payload, MediaType.APPLICATION_JSON_VALUE));
    }

    private PlatformAuthenticatedSession require(HttpServletRequest request, String permission) {
        Object candidate = request == null ? null
                : request.getAttribute(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE);
        if (!(candidate instanceof PlatformAuthenticatedSession session)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "live ReachAI platform login is required");
        }
        authorizationService.requireGlobalPermission(session, permission);
        return session;
    }

    private void audit(PlatformAuthenticatedSession actor,
                       String eventType,
                       String target,
                       Map<String, ?> details) {
        auditService.record(actor, eventType, "KNOWLEDGE_BUSINESS_INDEX", sha256(target), details);
    }

    private byte[] jsonBody(JsonNode body) {
        if (body == null || body.isNull()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "JSON request body is required");
        }
        try {
            return objectMapper.writeValueAsBytes(body);
        } catch (Exception invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "JSON request body is invalid", invalid);
        }
    }

    private String recordHash(String indexCode, String dataJson) {
        try {
            JsonNode root = objectMapper.readTree(dataJson);
            String bizId = requiredSegment(root == null ? null : root.path("bizId").asText(null),
                    "bizId", 256);
            return sha256(indexCode + "\n" + bizId);
        } catch (ResponseStatusException expected) {
            throw expected;
        } catch (Exception invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "business-index data part is invalid JSON", invalid);
        }
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

    private static String path(String indexCode) {
        return KnowledgeBizIndexGateway.INTERNAL_ROOT + "/" + encode(indexCode);
    }

    private static String normalizeIndexCode(String value) {
        String normalized = requiredSegment(value, "indexCode", 63);
        if (!normalized.matches("[A-Za-z][A-Za-z0-9_]{1,62}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "indexCode must contain 2-63 letters, digits, or underscores and start with a letter");
        }
        return normalized;
    }

    private static String normalizeTenant(String value) {
        String normalized = requiredSegment(value, "console tenant", 96);
        if (!normalized.matches("[A-Za-z0-9._:-]+")) {
            throw new IllegalArgumentException("console tenant contains unsupported characters");
        }
        return normalized;
    }

    private static String requiredSegment(String value, String field, int maxLength) {
        if (!StringUtils.hasText(value)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is required");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is invalid");
        }
        return normalized;
    }

    private static String encode(String value) {
        return UriUtils.encodePathSegment(value, StandardCharsets.UTF_8);
    }

    private static String actorId(PlatformAuthenticatedSession actor) {
        return String.valueOf(actor.user().getId());
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(String.valueOf(value).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
