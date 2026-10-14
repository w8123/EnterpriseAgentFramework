package com.enterprise.ai.control.capability;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformPermissions;
import com.enterprise.ai.control.identity.PlatformRequestAuthorization;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Scope claims come only from the server-attested session and travel in the HMAC-signed body. */
public final class CatalogReadAuthorization {
    public static final String PROJECT_ID = "X-EAF-Catalog-Project-Id";
    public static final String PROJECT_CODE = "X-EAF-Catalog-Project-Code";
    public static final String SCOPE = "X-EAF-Catalog-Scope";

    private CatalogReadAuthorization() { }

    public static Map<String, Object> body(PlatformAuthenticatedSession session, Long requestedProjectId) {
        return body(session, PlatformPermissions.PLATFORM_READ, requestedProjectId, false);
    }

    /** A named owner is unknown until Capability resolves it; the caller's required permission is unchanged. */
    public static Map<String, Object> resourceBody(PlatformAuthenticatedSession session, String permission) {
        return body(session, permission, null, true);
    }

    private static Map<String, Object> body(PlatformAuthenticatedSession session, String permission,
                                             Long requestedProjectId, boolean unknownOwner) {
        boolean global = session.hasGlobalPermission(permission);
        var projects = session.permissionGrants().stream()
                .filter(grant -> "PROJECT".equalsIgnoreCase(grant.scopeType()))
                .filter(grant -> grant.grantsResource(permission, "PROJECT", null, grant.scopeValue()))
                .map(grant -> grant.scopeValue().trim()).distinct().sorted().toList();
        if (!global && ((!unknownOwner && requestedProjectId == null) || projects.isEmpty())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Catalog read scope is not granted");
        }
        Map<String, Object> grant = new LinkedHashMap<>();
        grant.put("global", global);
        grant.put("projects", projects);
        grant.put("requestedProjectId", requestedProjectId);
        return Map.of("catalogReadGrant", grant);
    }

    public static boolean matchesOwner(ResponseEntity<Object> response, Long projectId, String projectCode) {
        if (response == null) return false;
        if (projectId == null) return projectCode == null && "GLOBAL".equals(response.getHeaders().getFirst(SCOPE))
                && response.getHeaders().getFirst(PROJECT_ID) == null && response.getHeaders().getFirst(PROJECT_CODE) == null;
        return projectId > 0 && projectCode != null && !projectCode.isBlank()
                && "PROJECT".equals(response.getHeaders().getFirst(SCOPE))
                && String.valueOf(projectId).equals(response.getHeaders().getFirst(PROJECT_ID))
                && projectCode.equals(projectCode(response));
    }

    /** Detail DTO identity must agree with the owner attested before the business handler ran. */
    public static ResponseEntity<Object> verifyOwnerResponse(HttpServletRequest request, String permission,
                                                             Long projectId, String projectCode,
                                                             ResponseEntity<Object> response,
                                                             PlatformRequestAuthorization authorization) {
        if (response == null || response.getStatusCode().is5xxServerError()) {
            return ResponseEntity.status(503).body(Map.of("code", "CAPABILITY_SERVICE_UNAVAILABLE"));
        }
        if (response.getStatusCode().is2xxSuccessful()) {
            if (!matchesOwner(response, projectId, projectCode)) return invalidOwner();
            if (projectId == null) authorization.requireGlobalPermission(request, permission);
            else authorization.requireResourcePermission(request, permission, "PROJECT", null, projectCode);
        }
        return ResponseEntity.status(response.getStatusCode()).body(response.getBody());
    }

    /** Recheck the owner identity after Capability has authorized it before running its handler. */
    public static ResponseEntity<Object> verifyResponse(HttpServletRequest request, Long requestedProjectId,
                                                       ResponseEntity<Object> response,
                                                       PlatformRequestAuthorization authorization) {
        if (response == null) {
            return ResponseEntity.status(503).body(Map.of("code", "CAPABILITY_SERVICE_UNAVAILABLE"));
        }
        if (response.getStatusCode().is5xxServerError()) {
            return ResponseEntity.status(503).body(Map.of("code", "CAPABILITY_SERVICE_UNAVAILABLE"));
        }
        if (response.getStatusCode().is2xxSuccessful()) {
            if (requestedProjectId == null) {
                if (!"GLOBAL".equals(response.getHeaders().getFirst(SCOPE))) return invalidOwner();
                authorization.requireGlobalPermission(request, PlatformPermissions.PLATFORM_READ);
            } else {
                String code = projectCode(response);
                if (!"PROJECT".equals(response.getHeaders().getFirst(SCOPE))
                        || !String.valueOf(requestedProjectId).equals(response.getHeaders().getFirst(PROJECT_ID))
                        || code == null) return invalidOwner();
                authorization.requireResourcePermission(request, PlatformPermissions.PLATFORM_READ, "PROJECT", null, code);
            }
        }
        return ResponseEntity.status(response.getStatusCode()).body(response.getBody());
    }

    public static String projectCode(ResponseEntity<Object> response) {
        String encoded = response == null ? null : response.getHeaders().getFirst(PROJECT_CODE);
        if (encoded == null || encoded.isBlank()) return null;
        try {
            String code = URLDecoder.decode(encoded, StandardCharsets.UTF_8);
            return code.isBlank() ? null : code;
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    private static ResponseEntity<Object> invalidOwner() {
        return ResponseEntity.status(502).body(Map.of("code", "CAPABILITY_PROJECT_RESPONSE_INVALID"));
    }
}
