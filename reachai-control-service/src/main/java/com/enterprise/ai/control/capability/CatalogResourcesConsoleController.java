package com.enterprise.ai.control.capability;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformPermissions;
import com.enterprise.ai.control.identity.PlatformRequestAuthorization;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Project-authorized public facade. The owning Capability service also validates every subject/node. */
@RestController
@RequiredArgsConstructor
public class CatalogResourcesConsoleController {
    private final CapabilityReviewGateway capability;
    private final PlatformRequestAuthorization authorization;

    @RequestMapping(path = {"/api/business-capabilities/projects/{projectId}/{*path}",
            "/api/api-graph/projects/{projectId}/{*path}"})
    public ResponseEntity<Object> resource(HttpServletRequest request, RequestEntity<Object> input,
                                          @PathVariable Long projectId, @PathVariable String path,
                                          @RequestParam Map<String, String> query) {
        boolean graph = request.getRequestURI().startsWith("/api/api-graph/");
        String method = request.getMethod().toUpperCase(java.util.Locale.ROOT);
        if (projectId == null || projectId <= 0 || !allowed(graph, method, path)) {
            return ResponseEntity.badRequest().body(Map.of("code", "CATALOG_RESOURCE_INVALID", "message", "业务能力资源路径无效"));
        }
        String permission = "GET".equals(method) ? PlatformPermissions.PLATFORM_READ : PlatformPermissions.PLATFORM_WRITE;
        PlatformAuthenticatedSession session = authorization.requirePermission(request, permission);
        if (session.user() == null || session.user().getId() == null) {
            return ResponseEntity.status(401).body(Map.of("code", "PLATFORM_SESSION_INVALID"));
        }
        String actor = String.valueOf(session.user().getId());
        Set<String> allowedQuery = graph ? Set.of("status", "minConfidence") : Set.of("force", "modelInstanceId", "taskId", "subjectIds");
        Map<String, String> parameters = new LinkedHashMap<>();
        query.forEach((name, value) -> { if (allowedQuery.contains(name)) parameters.put(name, value); });
        if ("GET".equals(method)) {
            ResponseEntity<Object> response = capability.catalogResources(method, projectId, graph, path, parameters,
                    CatalogReadAuthorization.body(session, projectId), actor);
            return CatalogReadAuthorization.verifyResponse(request, projectId, response, authorization);
        }
        ResponseEntity<Object> project = capability.getProjectById(projectId, actor);
        if (project == null) return unavailable();
        if (!project.getStatusCode().is2xxSuccessful()) return project;
        if (!(project.getBody() instanceof Map<?, ?> body) || !(body.get("projectId") instanceof Number returned)
                || !new java.math.BigDecimal(returned.toString()).equals(java.math.BigDecimal.valueOf(projectId))
                || !(body.get("projectCode") instanceof String code) || code.isBlank()) return invalidOwner();
        authorization.requireResourcePermission(request, permission, "PROJECT", null, code);
        return capability.catalogResources(method, projectId, graph, path, parameters, input.getBody(), actor);
    }

    static boolean allowed(boolean graph, String method, String path) {
        if (path == null) return false;
        if (graph) return switch (method) {
            case "GET" -> path.matches("/(snapshot|candidates)") || path.matches("/subjects/(BUSINESS_METHOD|HTTP_API|SOURCE_OBSERVATION)/[1-9][0-9]*/param-hints");
            case "POST" -> path.matches("/(infer|infer/request-response|regenerate|rebuild|edges)") || path.matches("/candidates/[1-9][0-9]*/(confirm|reject)");
            case "PUT" -> "/layout".equals(path);
            case "DELETE" -> path.matches("/edges/[1-9][0-9]*");
            default -> false;
        };
        return switch (method) {
            case "GET" -> path.matches("/(semantic-docs|semantic/status|groups)") || path.matches("/classification/(BUSINESS_METHOD|HTTP_API)(/assignments)?")
                    || path.matches("/subjects/(PROJECT|GROUP|BUSINESS_METHOD|HTTP_API|SOURCE_OBSERVATION)/[1-9][0-9]*/(enrichment|semantic)")
                    || path.matches("/subjects/(BUSINESS_METHOD|HTTP_API)/[1-9][0-9]*/classification");
            case "POST" -> "/groups".equals(path) || "/semantic/generate".equals(path)
                    || path.matches("/subjects/(PROJECT|GROUP|BUSINESS_METHOD|HTTP_API|SOURCE_OBSERVATION)/[1-9][0-9]*/semantic/generate");
            case "PUT" -> path.matches("/groups/[1-9][0-9]*") || path.matches("/subjects/(PROJECT|GROUP|BUSINESS_METHOD|HTTP_API|SOURCE_OBSERVATION)/[1-9][0-9]*/semantic")
                    || path.matches("/classification/(BUSINESS_METHOD|HTTP_API)");
            default -> false;
        };
    }
    private ResponseEntity<Object> invalidOwner() { return ResponseEntity.status(502).body(Map.of("code", "CAPABILITY_PROJECT_RESPONSE_INVALID")); }
    private ResponseEntity<Object> unavailable() { return ResponseEntity.status(503).body(Map.of("code", "CAPABILITY_SERVICE_UNAVAILABLE")); }
}
