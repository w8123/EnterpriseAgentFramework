package com.enterprise.ai.control.capability;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformPermissions;
import com.enterprise.ai.control.identity.PlatformRequestAuthorization;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigInteger;
import java.util.Map;

/**
 * Platform-session facade for the Capability catalog compatibility routes.
 *
 * <p>The Capability service owns the catalog data. Control resolves project
 * identity through the owner before applying a project grant and never lets
 * the legacy catch-all proxy decide whether a catalog read is allowed.</p>
 */
@RestController
@RequiredArgsConstructor
public class CapabilityCatalogConsoleController {

    private static final String PROJECT_SCOPE = "PROJECT";
    private static final String PROJECT_RESPONSE_INVALID = "CAPABILITY_PROJECT_RESPONSE_INVALID";
    private static final String CAPABILITY_RESPONSE_INVALID = "CAPABILITY_CATALOG_RESPONSE_INVALID";
    private static final String PROJECT_IDENTITY_MISMATCH = "CAPABILITY_PROJECT_IDENTITY_MISMATCH";
    private static final String PROJECT_IDENTITY_UNCONFIRMED = "CAPABILITY_PROJECT_IDENTITY_UNCONFIRMED";

    private final CapabilityReviewGateway capabilityReviewGateway;
    private final PlatformRequestAuthorization requestAuthorization;

    @GetMapping("/api/tools")
    public ResponseEntity<Object> list(
            HttpServletRequest request,
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) Long projectId) {
        return listCatalog(request, current, size, keyword, enabled, projectId,
                (page, pageSize, term, enabledOnly, selectedProjectId, actor) ->
                        capabilityReviewGateway.listCapabilities(
                                page, pageSize, term, source, enabledOnly, selectedProjectId, actor));
    }

    @GetMapping("/api/business-methods")
    public ResponseEntity<Object> listBusinessMethods(
            HttpServletRequest request,
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) Long projectId) {
        return listCatalog(request, current, size, keyword, enabled, projectId,
                capabilityReviewGateway::listBusinessMethods);
    }

    private ResponseEntity<Object> listCatalog(
            HttpServletRequest request,
            int current,
            int size,
            String keyword,
            Boolean enabled,
            Long projectId,
            CatalogListReader reader) {
        PlatformAuthenticatedSession authenticated = requestAuthorization.requireAuthenticated(request);
        String actor = actorId(authenticated);
        if (projectId != null && projectId <= 0) {
            return error(HttpStatus.BAD_REQUEST, "CAPABILITY_PROJECT_ID_INVALID",
                    "projectId must be positive");
        }

        if (projectId == null) {
            requestAuthorization.requireGlobalPermission(request, PlatformPermissions.PLATFORM_READ);
        } else {
            requestAuthorization.requirePermission(request, PlatformPermissions.PLATFORM_READ);
            ProjectResolution project = resolveProject(projectId, actor);
            if (project.failure() != null) {
                return project.failure();
            }
            requireProjectRead(request, project.summary());
        }
        return forwardCatalogResponse(reader.read(current, size, keyword, enabled, projectId, actor));
    }

    @GetMapping("/api/tools/{name}")
    public ResponseEntity<Object> get(HttpServletRequest request, @PathVariable String name) {
        return getCatalog(request, name, capabilityReviewGateway::getCapability);
    }

    @GetMapping("/api/business-methods/{name}")
    public ResponseEntity<Object> getBusinessMethod(HttpServletRequest request, @PathVariable String name) {
        return getCatalog(request, name, capabilityReviewGateway::getBusinessMethod);
    }

    private ResponseEntity<Object> getCatalog(
            HttpServletRequest request,
            String name,
            CatalogItemReader reader) {
        PlatformAuthenticatedSession authenticated = requestAuthorization.requirePermission(
                request, PlatformPermissions.PLATFORM_READ);
        String actor = actorId(authenticated);
        String requestedName = normalizeName(name);
        if (!StringUtils.hasText(requestedName)) {
            return error(HttpStatus.BAD_REQUEST, "CAPABILITY_NAME_REQUIRED",
                    "capability name is required");
        }

        ResponseEntity<Object> response = reader.read(requestedName, actor);
        if (response == null || !response.getStatusCode().is2xxSuccessful()) {
            return forwardCatalogResponse(response);
        }
        if (!(response.getBody() instanceof Map<?, ?> body)) {
            return error(HttpStatus.BAD_GATEWAY, CAPABILITY_RESPONSE_INVALID,
                    "Capability Service returned an invalid catalog response");
        }
        IdentityResolution identity = parseIdentity(body, requestedName);
        if (identity.failure() != null) {
            return identity.failure();
        }

        CatalogIdentity catalog = identity.identity();
        if (catalog.projectId() == null) {
            if (catalog.projectCode() != null) {
                return conflict(PROJECT_IDENTITY_UNCONFIRMED,
                        "Capability project identity is not confirmed by the owner");
            }
            requestAuthorization.requireGlobalPermission(request, PlatformPermissions.PLATFORM_READ);
            return response;
        }

        ProjectResolution project = resolveProject(catalog.projectId(), actor);
        if (project.failure() != null) {
            return project.failure();
        }
        if (catalog.projectCode() != null) {
            if (!StringUtils.hasText(project.summary().projectCode())) {
                return error(HttpStatus.BAD_GATEWAY, PROJECT_RESPONSE_INVALID,
                        "Capability Service returned an incomplete project response");
            }
            if (!catalog.projectCode().equals(project.summary().projectCode())) {
                return conflict(PROJECT_IDENTITY_MISMATCH,
                        "Capability project identity does not match the owning project");
            }
        }
        requireProjectRead(request, project.summary());
        return response;
    }

    private ProjectResolution resolveProject(Long projectId, String actor) {
        ResponseEntity<Object> response = capabilityReviewGateway.getProjectById(projectId, actor);
        if (response == null || !response.getStatusCode().is2xxSuccessful()) {
            return new ProjectResolution(null, forwardCatalogResponse(response));
        }
        if (!(response.getBody() instanceof Map<?, ?> body)) {
            return new ProjectResolution(null, error(HttpStatus.BAD_GATEWAY, PROJECT_RESPONSE_INVALID,
                    "Capability Service returned an invalid project response"));
        }
        Long responseProjectId = positiveInteger(body.get("projectId"));
        if (responseProjectId == null || !projectId.equals(responseProjectId)) {
            return new ProjectResolution(null, error(HttpStatus.BAD_GATEWAY, PROJECT_RESPONSE_INVALID,
                    "Capability Service returned an invalid project response"));
        }
        Object rawProjectCode = body.get("projectCode");
        if (rawProjectCode != null && !(rawProjectCode instanceof String)) {
            return new ProjectResolution(null, error(HttpStatus.BAD_GATEWAY, PROJECT_RESPONSE_INVALID,
                    "Capability Service returned an invalid project response"));
        }
        String projectCode = rawProjectCode instanceof String text
                ? trimToNull(text)
                : null;
        return new ProjectResolution(new ProjectSummary(responseProjectId, projectCode), null);
    }

    private IdentityResolution parseIdentity(Map<?, ?> body, String requestedName) {
        Object rawName = body.get("name");
        if (!(rawName instanceof String returnedName)) {
            return new IdentityResolution(null, error(HttpStatus.BAD_GATEWAY, CAPABILITY_RESPONSE_INVALID,
                    "Capability Service returned an invalid catalog response"));
        }
        Object rawQualifiedName = body.get("qualifiedName");
        if (rawQualifiedName != null && !(rawQualifiedName instanceof String)) {
            return new IdentityResolution(null, error(HttpStatus.BAD_GATEWAY, CAPABILITY_RESPONSE_INVALID,
                    "Capability Service returned an invalid catalog response"));
        }
        String returnedQualifiedName = rawQualifiedName instanceof String text ? trimToNull(text) : null;
        if (!requestedName.equals(returnedName) && !requestedName.equals(returnedQualifiedName)) {
            return new IdentityResolution(null, error(HttpStatus.BAD_GATEWAY, CAPABILITY_RESPONSE_INVALID,
                    "Capability Service returned an invalid catalog response"));
        }
        Object rawProjectId = body.get("projectId");
        Long projectId = rawProjectId == null ? null : positiveInteger(rawProjectId);
        if (rawProjectId != null && projectId == null) {
            return new IdentityResolution(null, error(HttpStatus.BAD_GATEWAY, CAPABILITY_RESPONSE_INVALID,
                    "Capability Service returned an invalid catalog response"));
        }
        Object rawProjectCode = body.get("projectCode");
        if (rawProjectCode != null && !(rawProjectCode instanceof String)) {
            return new IdentityResolution(null, error(HttpStatus.BAD_GATEWAY, CAPABILITY_RESPONSE_INVALID,
                    "Capability Service returned an invalid catalog response"));
        }
        String projectCode = rawProjectCode instanceof String text
                ? trimToNull(text)
                : null;
        return new IdentityResolution(new CatalogIdentity(projectId, projectCode), null);
    }

    private void requireProjectRead(HttpServletRequest request, ProjectSummary project) {
        if (StringUtils.hasText(project.projectCode())) {
            requestAuthorization.requireResourcePermission(
                    request, PlatformPermissions.PLATFORM_READ, PROJECT_SCOPE, null, project.projectCode());
        } else {
            requestAuthorization.requireGlobalPermission(request, PlatformPermissions.PLATFORM_READ);
        }
    }

    private ResponseEntity<Object> forwardCatalogResponse(ResponseEntity<Object> response) {
        if (response != null && response.getStatusCode().is2xxSuccessful()) {
            return response;
        }
        if (response != null && response.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        if (response != null && response.getStatusCode().value() >= 500) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "CAPABILITY_SERVICE_UNAVAILABLE",
                    "Capability Service is unavailable");
        }
        return error(HttpStatus.BAD_GATEWAY, "CAPABILITY_CATALOG_UPSTREAM_ERROR",
                "Capability catalog request failed");
    }

    private ResponseEntity<Object> conflict(String code, String message) {
        return error(HttpStatus.CONFLICT, code, message);
    }

    private ResponseEntity<Object> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of(
                "success", false,
                "code", code,
                "message", message));
    }

    private String actorId(PlatformAuthenticatedSession session) {
        if (session == null || session.user() == null || session.user().getId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "authenticated platform userId is required");
        }
        return String.valueOf(session.user().getId());
    }

    private String normalizeName(String value) {
        return value == null ? "" : value.trim();
    }

    private String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private Long positiveInteger(Object value) {
        if (value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long) {
            long candidate = ((Number) value).longValue();
            return candidate > 0 ? candidate : null;
        }
        if (value instanceof BigInteger candidate
                && candidate.signum() > 0
                && candidate.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) <= 0) {
            return candidate.longValue();
        }
        return null;
    }

    private record CatalogIdentity(Long projectId, String projectCode) {
    }

    private record ProjectSummary(Long projectId, String projectCode) {
    }

    private record ProjectResolution(ProjectSummary summary, ResponseEntity<Object> failure) {
    }

    private record IdentityResolution(CatalogIdentity identity, ResponseEntity<Object> failure) {
    }

    @FunctionalInterface
    private interface CatalogListReader {
        ResponseEntity<Object> read(
                int current,
                int size,
                String keyword,
                Boolean enabled,
                Long projectId,
                String actorId);
    }

    @FunctionalInterface
    private interface CatalogItemReader {
        ResponseEntity<Object> read(String name, String actorId);
    }
}
