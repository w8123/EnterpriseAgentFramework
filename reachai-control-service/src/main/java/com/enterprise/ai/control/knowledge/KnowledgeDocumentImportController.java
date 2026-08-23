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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** Authenticated and resource-scoped public BFF for durable document imports. */
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeDocumentImportController {

    static final String READ_PERMISSION = "platform:read";
    static final String WRITE_PERMISSION = "platform:write";

    private final KnowledgeBizIndexGateway gateway;
    private final PlatformAuthorizationService authorizationService;
    private final PlatformAuthAuditService auditService;
    private final ObjectMapper objectMapper;
    private final String consoleTenantId;

    public KnowledgeDocumentImportController(
            KnowledgeBizIndexGateway gateway,
            PlatformAuthorizationService authorizationService,
            PlatformAuthAuditService auditService,
            ObjectMapper objectMapper,
            @Value("${reachai.knowledge.console-ingress.tenant-id:default}") String consoleTenantId) {
        this.gateway = gateway;
        this.authorizationService = authorizationService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.consoleTenantId = requiredIdentifier(consoleTenantId, "console tenant", 96);
    }

    @PostMapping("/import-jobs")
    public ResponseEntity<byte[]> submit(
            HttpServletRequest request,
            @RequestParam("file") MultipartFile file,
            @RequestParam("knowledgeBaseCode") String knowledgeBaseCode,
            @RequestParam(value = "chunkStrategy", defaultValue = "fixed_length") String chunkStrategy,
            @RequestParam(value = "chunkSize", defaultValue = "500") Integer chunkSize,
            @RequestParam(value = "chunkOverlap", defaultValue = "50") Integer chunkOverlap,
            @RequestParam(value = "extraParams", required = false) String extraParamsJson) {
        PlatformAuthenticatedSession actor = requireSession(request);
        String code = requiredIdentifier(knowledgeBaseCode, "knowledgeBaseCode", 64);
        ResourceScope scope = authorizeScope(actor, WRITE_PERMISSION,
                KnowledgeBizIndexGateway.DOCUMENT_IMPORT_INTERNAL_ROOT
                        + "/knowledge-bases/" + encode(code) + "/scope");
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("knowledgeBaseCode", code);
        fields.put("chunkStrategy", chunkStrategy == null ? "" : chunkStrategy);
        fields.put("chunkSize", String.valueOf(chunkSize));
        fields.put("chunkOverlap", String.valueOf(chunkOverlap));
        fields.put("extraParams", extraParamsJson == null ? "" : extraParamsJson);
        fields.put("autoCommit", "false");
        fields.put("workspaceId", scope.workspaceId());
        fields.put("projectCode", scope.projectCode());
        fields.put("resourceScope", scope.scope());
        audit(actor, "KNOWLEDGE_DOCUMENT_IMPORT_SUBMITTED", code,
                Map.of("knowledgeBaseCode", code,
                        "fileSize", file == null ? 0L : file.getSize(),
                        "resourceScope", scope.scope()));
        return respond(gateway.exchangeDocumentMultipart(
                KnowledgeBizIndexGateway.DOCUMENT_IMPORT_INTERNAL_ROOT + "/jobs",
                consoleTenantId,
                actorId(actor),
                file,
                fields));
    }

    @GetMapping("/import-jobs/{jobId}")
    public ResponseEntity<byte[]> get(HttpServletRequest request,
                                      @PathVariable String jobId,
                                      @RequestParam(defaultValue = "false") boolean includePreview) {
        PlatformAuthenticatedSession actor = requireSession(request);
        String id = requiredIdentifier(jobId, "jobId", 64);
        authorizeScope(actor, READ_PERMISSION, jobScopePath(id));
        String path = jobPath(id) + (includePreview ? "/preview" : "");
        return respond(gateway.exchange("GET", path, consoleTenantId, actorId(actor), null, null));
    }

    @PostMapping("/import-jobs/{jobId}/commit")
    public ResponseEntity<byte[]> commit(HttpServletRequest request, @PathVariable String jobId) {
        return mutateJob(request, jobId, "commit", "KNOWLEDGE_DOCUMENT_IMPORT_COMMITTED");
    }

    @PostMapping("/import-jobs/{jobId}/retry")
    public ResponseEntity<byte[]> retry(HttpServletRequest request, @PathVariable String jobId) {
        return mutateJob(request, jobId, "retry", "KNOWLEDGE_DOCUMENT_IMPORT_RETRIED");
    }

    @PostMapping("/import-jobs/{jobId}/cancel")
    public ResponseEntity<byte[]> cancel(HttpServletRequest request, @PathVariable String jobId) {
        return mutateJob(request, jobId, "cancel", "KNOWLEDGE_DOCUMENT_IMPORT_CANCELLED");
    }

    @PostMapping("/import-files/{fileId}/reparse")
    public ResponseEntity<byte[]> reparse(HttpServletRequest request, @PathVariable String fileId) {
        PlatformAuthenticatedSession actor = requireSession(request);
        String id = requiredIdentifier(fileId, "fileId", 128);
        ResourceScope scope = authorizeScope(actor, WRITE_PERMISSION,
                KnowledgeBizIndexGateway.DOCUMENT_IMPORT_INTERNAL_ROOT
                        + "/files/" + encode(id) + "/scope");
        Map<String, Object> assertion = new LinkedHashMap<>();
        assertion.put("workspaceId", scope.workspaceId());
        assertion.put("projectCode", scope.projectCode());
        assertion.put("resourceScope", scope.scope());
        byte[] body = jsonBody(assertion);
        audit(actor, "KNOWLEDGE_DOCUMENT_REPARSE_REQUESTED", id,
                Map.of("resourceScope", scope.scope()));
        return respond(gateway.exchange("POST",
                KnowledgeBizIndexGateway.DOCUMENT_IMPORT_INTERNAL_ROOT
                        + "/files/" + encode(id) + "/reparse",
                consoleTenantId,
                actorId(actor),
                body,
                MediaType.APPLICATION_JSON_VALUE));
    }

    private ResponseEntity<byte[]> mutateJob(HttpServletRequest request,
                                             String rawJobId,
                                             String operation,
                                             String eventType) {
        PlatformAuthenticatedSession actor = requireSession(request);
        String jobId = requiredIdentifier(rawJobId, "jobId", 64);
        authorizeScope(actor, WRITE_PERMISSION, jobScopePath(jobId));
        audit(actor, eventType, jobId, Map.of());
        return respond(gateway.exchange("POST", jobPath(jobId) + "/" + operation,
                consoleTenantId, actorId(actor), null, null));
    }

    private ResourceScope authorizeScope(PlatformAuthenticatedSession actor,
                                         String permission,
                                         String scopePath) {
        KnowledgeBizIndexGateway.GatewayResponse response = gateway.exchange(
                "GET", scopePath, consoleTenantId, actorId(actor), null, null);
        ResourceScope scope = parseScope(response);
        authorizationService.requireResourcePermission(
                actor, permission, scope.scope(), scope.workspaceId(), scope.projectCode());
        return scope;
    }

    private ResourceScope parseScope(KnowledgeBizIndexGateway.GatewayResponse response) {
        if (response.status() < 200 || response.status() >= 300) {
            HttpStatus status = HttpStatus.resolve(response.status());
            throw new ResponseStatusException(status == null ? HttpStatus.BAD_GATEWAY : status,
                    "Knowledge resource scope could not be resolved");
        }
        try {
            JsonNode data = objectMapper.readTree(response.body()).path("data");
            String workspaceId = requiredIdentifier(data.path("workspaceId").asText(null), "workspaceId", 64);
            String scope = requiredIdentifier(data.path("scope").asText(null), "resourceScope", 20)
                    .toUpperCase(java.util.Locale.ROOT);
            if (!scope.equals("SHARED") && !scope.equals("WORKSPACE") && !scope.equals("PROJECT")) {
                throw new IllegalArgumentException("unsupported Knowledge resource scope");
            }
            String projectCode = optionalIdentifier(data.path("projectCode").asText(null), 64);
            if (scope.equals("PROJECT") && projectCode == null) {
                throw new IllegalArgumentException("project-scoped Knowledge resource has no projectCode");
            }
            return new ResourceScope(workspaceId, projectCode, scope);
        } catch (ResponseStatusException expected) {
            throw expected;
        } catch (Exception invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Knowledge resource scope response is invalid", invalid);
        }
    }

    private PlatformAuthenticatedSession requireSession(HttpServletRequest request) {
        Object candidate = request == null ? null
                : request.getAttribute(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE);
        if (!(candidate instanceof PlatformAuthenticatedSession session)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "live ReachAI platform login is required");
        }
        return session;
    }

    private void audit(PlatformAuthenticatedSession actor,
                       String eventType,
                       String target,
                       Map<String, ?> details) {
        auditService.record(actor, eventType, "KNOWLEDGE_DOCUMENT_IMPORT", sha256(target), details);
    }

    private byte[] jsonBody(Object body) {
        try {
            return objectMapper.writeValueAsBytes(body);
        } catch (Exception invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request body is invalid", invalid);
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

    private static String jobPath(String jobId) {
        return KnowledgeBizIndexGateway.DOCUMENT_IMPORT_INTERNAL_ROOT + "/jobs/" + encode(jobId);
    }

    private static String jobScopePath(String jobId) {
        return jobPath(jobId) + "/scope";
    }

    private static String requiredIdentifier(String value, String field, int maxLength) {
        if (!StringUtils.hasText(value)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is required");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength
                || normalized.chars().anyMatch(Character::isISOControl)
                || !normalized.matches("[A-Za-z0-9._:-]+")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is invalid");
        }
        return normalized;
    }

    private static String optionalIdentifier(String value, int maxLength) {
        return StringUtils.hasText(value) ? requiredIdentifier(value, "projectCode", maxLength) : null;
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

    private record ResourceScope(String workspaceId, String projectCode, String scope) {
    }
}
