package com.enterprise.ai.control.compat;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformConsolePermissionPolicy;
import com.enterprise.ai.control.mcp.application.port.McpPlatformBusinessSystemReadPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Set;

/** Reads only the paths a console user could GET, after the same path policy admits the delegate. */
@Component
@RequiredArgsConstructor
final class CapabilityMcpPlatformBusinessSystemReadAdapter implements McpPlatformBusinessSystemReadPort {
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final Map<Operation, Set<String>> QUERY = Map.of(
            Operation.PROJECT, Set.of(),
            Operation.OPERATION_BLOCKERS, Set.of("operation"),
            Operation.TOOLS, Set.of("view"),
            Operation.TOOL, Set.of(),
            Operation.SOURCES, Set.of("current", "size", "keyword", "moduleId", "ungrouped"),
            Operation.SENSITIVE_DATA_STATUS, Set.of("taskId"));
    private final CapabilityCompatibilityProxyController proxy;
    private final ObjectMapper json;

    @Override
    public ResponseEntity<?> act(PlatformAuthenticatedSession actor, Action action, long projectId, Long scanToolId, Map<String, String> query) {
        boolean needsTool = action == Action.TOOL_RESCAN_FROM_SOURCE;
        boolean needsModel = action == Action.SENSITIVE_DATA_SCAN;
        if (projectId < 1 || needsTool != (scanToolId != null) || needsTool && scanToolId < 1
                || !(needsModel ? query.keySet().equals(Set.of("modelInstanceId")) : query.isEmpty())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid business system action");
        }
        String path = "/api/scan-projects/" + projectId + switch (action) {
            case RESCAN -> "/rescan";
            case RECONCILE_TOOLS -> "/tools/reconcile";
            case SDK_ACCESS_CHECK -> "/sdk-access-check";
            case SDK_SYNC_SCAN -> "/sdk-sync/scan";
            case TOOL_RESCAN_FROM_SOURCE -> "/scan-tools/" + scanToolId + "/rescan-from-source";
            case SENSITIVE_DATA_SCAN -> "/sensitive-data/scan";
        };
        var rule = PlatformConsolePermissionPolicy.ruleFor(path).orElseThrow();
        if (actor == null || actor.user() == null || !PlatformConsolePermissionPolicy.admits(rule, "POST", path, actor)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "business system write permission is required");
        }
        ResponseEntity<byte[]> response;
        try { response = proxy.consolePost(path, query); }
        catch (RuntimeException lost) { response = null; }
        if (response == null || response.getStatusCode().is5xxServerError()) {
            // Scans may still be running or may have completed; read the business system instead of resending.
            return ResponseEntity.accepted().body(Map.of("status", "UNKNOWN", "projectId", projectId,
                    "message", "操作结果未确认，请重新读取业务系统状态；不要直接再次执行。"));
        }
        if (!response.getStatusCode().is2xxSuccessful()) {
            // Owner messages can carry scanner paths or upstream errors; only stable codes cross the boundary.
            return response.getStatusCode().value() == 409
                    ? ResponseEntity.status(409).body(Map.of("code", "BUSINESS_SYSTEM_OPERATION_BLOCKED"))
                    : ResponseEntity.status(response.getStatusCode()).build();
        }
        try {
            byte[] body = response.getBody();
            if (body == null || body.length == 0) return ResponseEntity.ok(Map.of());
            if (body.length > MAX_RESPONSE_BYTES) return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
            return ResponseEntity.ok(json.readTree(body));
        } catch (Exception invalid) {
            return ResponseEntity.accepted().body(Map.of("status", "UNKNOWN", "projectId", projectId,
                    "message", "操作结果未确认，请重新读取业务系统状态；不要直接再次执行。"));
        }
    }

    @Override
    public boolean admits(PlatformAuthenticatedSession actor, Change change, Long projectId) {
        String path = changePath(change, projectId);
        var rule = PlatformConsolePermissionPolicy.ruleFor(path).orElse(null);
        return rule != null && actor != null && actor.user() != null
                && PlatformConsolePermissionPolicy.admits(rule, changeMethod(change).name(), path, actor);
    }

    @Override
    public ResponseEntity<?> change(PlatformAuthenticatedSession actor, Change change, Long projectId, Object body) {
        if (!admits(actor, change, projectId)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "business system write permission is required");
        ResponseEntity<byte[]> response;
        try { response = proxy.consoleSend(changeMethod(change), changePath(change, projectId), Map.of(), body); }
        catch (RuntimeException lost) { return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build(); }
        if (response == null || response.getStatusCode().is5xxServerError()) return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        if (!response.getStatusCode().is2xxSuccessful()) {
            return response.getStatusCode().value() == 409
                    ? ResponseEntity.status(409).body(Map.of("code", "BUSINESS_SYSTEM_OPERATION_BLOCKED"))
                    : ResponseEntity.status(response.getStatusCode()).build();
        }
        byte[] payload = response.getBody();
        if (payload == null || payload.length == 0) return ResponseEntity.status(response.getStatusCode()).build();
        if (payload.length > MAX_RESPONSE_BYTES) return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        try { return ResponseEntity.status(response.getStatusCode()).body(json.readTree(payload)); }
        catch (Exception invalid) { return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build(); }
    }

    private static String changePath(Change change, Long projectId) {
        if (change == Change.CREATE ? projectId != null : projectId == null || projectId < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid business system");
        }
        return switch (change) {
            case CREATE -> "/api/scan-projects";
            case UPDATE, DELETE -> "/api/scan-projects/" + projectId;
            case SCAN_SETTINGS -> "/api/scan-projects/" + projectId + "/scan-settings";
            case REGISTRY_CREDENTIAL -> "/api/scan-projects/" + projectId + "/registry-credential";
        };
    }

    private static HttpMethod changeMethod(Change change) {
        return switch (change) {
            case CREATE -> HttpMethod.POST;
            case UPDATE -> HttpMethod.PUT;
            case SCAN_SETTINGS, REGISTRY_CREDENTIAL -> HttpMethod.PATCH;
            case DELETE -> HttpMethod.DELETE;
        };
    }

    @Override
    public ResponseEntity<?> read(PlatformAuthenticatedSession actor, Operation operation, long projectId, Long scanToolId, Map<String, String> query) {
        if (projectId < 1 || operation == Operation.TOOL && (scanToolId == null || scanToolId < 1)
                || !QUERY.get(operation).containsAll(query.keySet())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid business system read");
        }
        String path = "/api/scan-projects/" + projectId + switch (operation) {
            case PROJECT -> "";
            case OPERATION_BLOCKERS -> "/operation-blockers";
            case TOOLS -> "/tools";
            case TOOL -> "/scan-tools/" + scanToolId;
            case SOURCES -> "/sources";
            case SENSITIVE_DATA_STATUS -> "/sensitive-data/status";
        };
        var rule = PlatformConsolePermissionPolicy.ruleFor(path).orElseThrow();
        if (actor == null || actor.user() == null || !PlatformConsolePermissionPolicy.admits(rule, "GET", path, actor)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "business system read permission is required");
        }
        ResponseEntity<byte[]> response = proxy.consoleGet(path, query);
        if (response == null) return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        if (!response.getStatusCode().is2xxSuccessful()) return ResponseEntity.status(response.getStatusCode()).build();
        try {
            byte[] body = response.getBody();
            if (body == null || body.length == 0 || body.length > MAX_RESPONSE_BYTES) return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
            return ResponseEntity.ok(json.readTree(body));
        } catch (Exception invalid) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        }
    }
}
