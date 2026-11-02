package com.enterprise.ai.control.compat;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformConsolePermissionPolicy;
import com.enterprise.ai.control.mcp.application.port.McpPlatformProjectCatalogPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Filters the existing sanitized project picker using the delegate's live resource grants. */
@Component
@RequiredArgsConstructor
final class CapabilityMcpPlatformProjectCatalogAdapter implements McpPlatformProjectCatalogPort {
    private static final String PROJECT_LIST = "/api/scan-projects";
    private static final List<String> FIELDS = List.of(
            "id", "name", "projectCode", "projectKind", "environment", "status", "description");
    private final CapabilityCompatibilityProxyController projects;
    private final ObjectMapper objectMapper;

    @Override
    public ResponseEntity<?> listProjects(PlatformAuthenticatedSession actor, int offset, int limit) {
        if (offset < 0 || limit < 1 || limit > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid project catalog page");
        }
        var rule = PlatformConsolePermissionPolicy.ruleFor(PROJECT_LIST).orElseThrow();
        if (actor == null || actor.user() == null || actor.user().getId() == null
                || !PlatformConsolePermissionPolicy.admits(rule, "GET", PROJECT_LIST, actor)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "project catalog permission is required");
        }
        ResponseEntity<byte[]> response = projects.projectPicker();
        if (response == null || !response.getStatusCode().is2xxSuccessful()) return response;
        JsonNode root;
        try {
            root = objectMapper.readTree(response.getBody());
        } catch (IOException | IllegalArgumentException failure) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        }
        if (root == null || !root.isArray()) return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        List<Map<String, Object>> visible = new ArrayList<>();
        for (JsonNode item : root) {
            JsonNode codeValue = item.path("projectCode");
            JsonNode id = item.path("id");
            if (!item.isObject() || !codeValue.isTextual() || codeValue.asText().isBlank()
                    || !id.isIntegralNumber() || !id.canConvertToLong() || id.longValue() <= 0) continue;
            String code = codeValue.asText().trim();
            if (rule.read().stream().noneMatch(permission ->
                    actor.hasResourcePermission(permission, "PROJECT", null, code))) continue;
            Map<String, Object> projection = new LinkedHashMap<>();
            for (String field : FIELDS) {
                JsonNode value = item.get(field);
                if (value != null && !value.isNull() && (value.isTextual() || value.isIntegralNumber())) {
                    projection.put(field, objectMapper.convertValue(value, Object.class));
                }
            }
            visible.add(projection);
        }
        int start = Math.min(offset, visible.size());
        int end = (int) Math.min((long) start + limit, visible.size());
        boolean unresolvedWorkspaceScopes = actor.permissionGrants().stream().anyMatch(grant ->
                "WORKSPACE".equalsIgnoreCase(grant.scopeType())
                        && ("*".equals(grant.permissionCode()) || rule.read().contains(grant.permissionCode())));
        return ResponseEntity.ok(Map.of("projects", List.copyOf(visible.subList(start, end)),
                "offset", offset, "hasMore", end < visible.size(),
                "unresolvedWorkspaceScopes", unresolvedWorkspaceScopes));
    }
}
