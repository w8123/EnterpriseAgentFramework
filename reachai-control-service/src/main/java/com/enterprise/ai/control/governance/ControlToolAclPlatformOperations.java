package com.enterprise.ai.control.governance;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformConsolePermissionPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Tool call permission rules for platform AI delegates: calls the console controller only after the console path policy
 * admits the delegate for the same method and path. Results are plain maps so callers outside this module never see
 * the persistence types.
 */
@Component
@RequiredArgsConstructor
public class ControlToolAclPlatformOperations {
    public enum Operation { CREATE, UPDATE, TOGGLE, DELETE }

    private final ControlToolAclController acl;
    private final ControlToolAclMapper mapper;

    public ResponseEntity<?> page(PlatformAuthenticatedSession actor, int current, int size, String roleCode, String targetKind) {
        admit(actor, "GET", "/api/tool-acl");
        return invoke(() -> acl.page(current, size, roleCode, targetKind));
    }

    public ResponseEntity<?> roles(PlatformAuthenticatedSession actor) {
        admit(actor, "GET", "/api/tool-acl/roles");
        return acl.roles();
    }

    public ResponseEntity<?> explain(PlatformAuthenticatedSession actor, List<String> roles, List<Map<String, String>> targets, Long projectId, String projectCode) {
        // The console sends explain as POST, but it only reads; read permission is what the page needs to show it.
        admit(actor, "GET", "/api/tool-acl");
        var refs = targets.stream().map(target -> new ControlToolAclController.ToolAclTargetRef(target.get("kind"), target.get("name"))).toList();
        return invoke(() -> acl.explain(new ControlToolAclController.ExplainRequest(roles, refs, projectId, projectCode)));
    }

    public ResponseEntity<?> find(PlatformAuthenticatedSession actor, long id) {
        admit(actor, "GET", "/api/tool-acl/" + id);
        ControlToolAclEntity entity = mapper.selectById(id);
        if (entity == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(row(entity));
    }

    /** The stored rules a batch grant would overwrite: same role, same project scope, one of the given targets. */
    public List<Map<String, Object>> batchExisting(PlatformAuthenticatedSession actor, String roleCode, Long projectId, String projectCode,
                                                   List<Map<String, String>> targets) {
        admit(actor, "GET", "/api/tool-acl");
        var rows = new java.util.ArrayList<Map<String, Object>>();
        for (var target : targets) {
            ControlToolAclEntity entity = mapper.selectOne(new LambdaQueryWrapper<ControlToolAclEntity>()
                    .eq(ControlToolAclEntity::getRoleCode, roleCode)
                    .eq(ControlToolAclEntity::getTargetKind, target.get("kind"))
                    .eq(ControlToolAclEntity::getTargetName, target.get("name"))
                    .eq(projectId != null, ControlToolAclEntity::getProjectId, projectId)
                    .isNull(projectId == null, ControlToolAclEntity::getProjectId)
                    .eq(projectCode != null, ControlToolAclEntity::getProjectCode, projectCode)
                    .isNull(projectCode == null, ControlToolAclEntity::getProjectCode)
                    .last("limit 1"));
            if (entity != null) rows.add(row(entity));
        }
        return rows;
    }

    public ResponseEntity<?> grantBatch(PlatformAuthenticatedSession actor, Map<String, Object> command) {
        admit(actor, "POST", "/api/tool-acl/batch");
        @SuppressWarnings("unchecked") var targets = ((List<Map<String, String>>) command.get("targets")).stream()
                .map(target -> new ControlToolAclController.ToolAclTargetRef(target.get("kind"), target.get("name"))).toList();
        return invoke(() -> acl.grantBatch(new ControlToolAclController.GrantBatchRequest((String) command.get("roleCode"),
                (String) command.get("permission"), targets, (String) command.get("note"),
                command.get("projectId") == null ? null : ((Number) command.get("projectId")).longValue(), (String) command.get("projectCode"))));
    }

    private static Map<String, Object> row(ControlToolAclEntity entity) {
        var row = new java.util.LinkedHashMap<String, Object>();
        row.put("id", entity.getId()); row.put("roleCode", entity.getRoleCode()); row.put("projectId", entity.getProjectId());
        row.put("projectCode", entity.getProjectCode()); row.put("targetKind", entity.getTargetKind()); row.put("targetName", entity.getTargetName());
        row.put("permission", entity.getPermission()); row.put("note", entity.getNote()); row.put("enabled", entity.getEnabled());
        row.put("updatedAt", entity.getUpdatedAt() == null ? null : entity.getUpdatedAt().toString());
        return row;
    }

    public ResponseEntity<?> change(PlatformAuthenticatedSession actor, Operation operation, Long id, Map<String, Object> rule) {
        return switch (operation) {
            case CREATE -> { admit(actor, "POST", "/api/tool-acl"); yield invoke(() -> acl.create(entity(new ControlToolAclEntity(), rule))); }
            case UPDATE -> {
                admit(actor, "PUT", "/api/tool-acl/" + id);
                var request = new ControlToolAclController.ToolAclUpdateRequest();
                entity(request, rule);
                yield invoke(() -> acl.update(id, request));
            }
            case TOGGLE -> {
                admit(actor, "POST", "/api/tool-acl/" + id + "/toggle");
                yield invoke(() -> acl.toggle(id, new ControlToolAclController.ToggleRequest((Boolean) rule.get("enabled"))));
            }
            case DELETE -> { admit(actor, "DELETE", "/api/tool-acl/" + id); yield invoke(() -> acl.delete(id)); }
        };
    }

    private static ControlToolAclEntity entity(ControlToolAclEntity entity, Map<String, Object> rule) {
        entity.setRoleCode((String) rule.get("roleCode"));
        // Both project fields are always set so an update states the scope explicitly, as the console form does.
        entity.setProjectId(rule.get("projectId") == null ? null : ((Number) rule.get("projectId")).longValue());
        entity.setProjectCode((String) rule.get("projectCode"));
        entity.setTargetKind((String) rule.get("targetKind"));
        entity.setTargetName((String) rule.get("targetName"));
        entity.setPermission((String) rule.get("permission"));
        entity.setNote((String) rule.get("note"));
        entity.setEnabled((Boolean) rule.get("enabled"));
        return entity;
    }

    private static void admit(PlatformAuthenticatedSession actor, String method, String path) {
        var rule = PlatformConsolePermissionPolicy.ruleFor(path).orElseThrow();
        if (actor == null || actor.user() == null || !PlatformConsolePermissionPolicy.admits(rule, method, path, actor)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "tool call permission rules are outside the caller's permissions");
        }
    }

    private static ResponseEntity<?> invoke(java.util.function.Supplier<ResponseEntity<?>> action) {
        try { return action.get(); }
        catch (IllegalArgumentException invalid) { return ResponseEntity.badRequest().build(); }
    }
}
