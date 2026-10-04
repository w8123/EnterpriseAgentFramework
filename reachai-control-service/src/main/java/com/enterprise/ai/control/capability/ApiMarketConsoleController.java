package com.enterprise.ai.control.capability;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformPermissions;
import com.enterprise.ai.control.identity.PlatformRequestAuthorization;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Global published discovery, project-scoped integration intent. Capability remains the sole owner. */
@RestController
@RequestMapping("/api/api-market")
@RequiredArgsConstructor
public class ApiMarketConsoleController {
    private final CapabilityReviewGateway capability;
    private final PlatformRequestAuthorization authorization;

    @GetMapping("/entries")
    public ResponseEntity<Object> entries(HttpServletRequest request, @RequestParam Map<String, String> parameters) {
        var session = authorization.requirePermission(request, PlatformPermissions.PLATFORM_READ);
        if (!Set.of("current", "size", "keyword", "category", "authType", "verificationStatus", "specStatus", "source", "specAvailable").containsAll(parameters.keySet())) return invalid();
        return capability.readApiMarket("/entries", parameters, actor(session));
    }

    @GetMapping("/entries/{entryKey}")
    public ResponseEntity<Object> entry(HttpServletRequest request, @PathVariable String entryKey) {
        var session = authorization.requirePermission(request, PlatformPermissions.PLATFORM_READ);
        if (!entryKey.matches("[a-z0-9][a-z0-9._-]{0,159}")) return invalid();
        return capability.readApiMarket("/entries/" + entryKey, Map.of(), actor(session));
    }

    @GetMapping({"/stats", "/categories", "/sources"})
    public ResponseEntity<Object> discovery(HttpServletRequest request) {
        var session = authorization.requirePermission(request, PlatformPermissions.PLATFORM_READ);
        String path = request.getRequestURI();
        return capability.readApiMarket(path.substring(path.lastIndexOf('/')), Map.of(), actor(session));
    }

    @GetMapping("/integrations")
    public ResponseEntity<Object> integrations(HttpServletRequest request, @RequestParam Long projectId,
            @RequestParam(required = false) String projectCode, @RequestParam(required = false) String status) {
        var session = authorization.requirePermission(request, PlatformPermissions.PLATFORM_READ);
        if (projectId == null || projectId <= 0) return invalid();
        var project = capability.getProjectById(projectId, actor(session));
        if (!project.getStatusCode().is2xxSuccessful()) return project;
        String trustedCode = projectCode(project.getBody());
        if (trustedCode == null || projectCode != null && !Objects.equals(projectCode, trustedCode)) return invalid();
        authorization.requireResourcePermission(request, PlatformPermissions.PLATFORM_READ, "PROJECT", null, trustedCode);
        Map<String, String> parameters = new LinkedHashMap<>(); parameters.put("projectId", String.valueOf(projectId));
        parameters.put("projectCode", trustedCode); if (status != null) parameters.put("status", status);
        return capability.readApiMarket("/integrations", parameters, actor(session));
    }

    @PostMapping("/entries/{entryKey}/integrations")
    public ResponseEntity<Object> select(HttpServletRequest request, @PathVariable String entryKey,
            @RequestBody Map<String, Object> body) {
        var session = authorization.requirePermission(request, PlatformPermissions.PLATFORM_WRITE);
        if (!entryKey.matches("[a-z0-9][a-z0-9._-]{0,159}") || body == null
                || !Set.of("projectId", "projectCode", "versionId", "operationIds", "environment", "note").containsAll(body.keySet())
                || !(body.get("projectId") instanceof Number id) || id.longValue() <= 0 || id.doubleValue() != id.longValue()) return invalid();
        var project = capability.getProjectById(id.longValue(), actor(session));
        if (!project.getStatusCode().is2xxSuccessful()) return project;
        String trustedCode = projectCode(project.getBody());
        if (trustedCode == null || !Objects.equals(body.get("projectCode"), trustedCode)) return invalid();
        authorization.requireResourcePermission(request, PlatformPermissions.PLATFORM_WRITE, "PROJECT", null, trustedCode);
        return capability.selectApiMarket(entryKey, body, actor(session));
    }

    @GetMapping("/integrations/{id}")
    public ResponseEntity<Object> integration(HttpServletRequest request, @PathVariable long id) {
        var session = authorization.requirePermission(request, PlatformPermissions.PLATFORM_READ);
        if (id <= 0) return invalid();
        var owner = capability.readApiMarket("/integrations/" + id, Map.of(), actor(session));
        if (!owner.getStatusCode().is2xxSuccessful()) return owner;
        String trustedCode = projectCode(owner.getBody());
        if (trustedCode == null) return invalid();
        authorization.requireResourcePermission(request, PlatformPermissions.PLATFORM_READ, "PROJECT", null, trustedCode);
        return owner;
    }

    @PutMapping("/integrations/{id}/status")
    public ResponseEntity<Object> status(HttpServletRequest request, @PathVariable long id, @RequestBody Map<String, Object> body) {
        var session = authorization.requirePermission(request, PlatformPermissions.PLATFORM_WRITE);
        if (id <= 0 || body == null || !Set.of("status", "note").containsAll(body.keySet())) return invalid();
        var owner = capability.readApiMarket("/integrations/" + id, Map.of(), actor(session));
        if (!owner.getStatusCode().is2xxSuccessful()) return owner;
        String trustedCode = projectCode(owner.getBody());
        if (trustedCode == null) return invalid();
        authorization.requireResourcePermission(request, PlatformPermissions.PLATFORM_WRITE, "PROJECT", null, trustedCode);
        return capability.updateApiMarketStatus(id, body, actor(session));
    }

    private String projectCode(Object value) {
        return value instanceof Map<?, ?> map && map.get("projectCode") instanceof String code && !code.isBlank() ? code : null;
    }
    private String actor(PlatformAuthenticatedSession session) { return String.valueOf(session.user().getId()); }
    private ResponseEntity<Object> invalid() {
        return ResponseEntity.badRequest().body(Map.of("code", "API_MARKET_REQUEST_INVALID",
                "message", "请选择服务器确认的项目/目录范围；不能提交自制 owner、origin、认证或验证 proof"));
    }
}
