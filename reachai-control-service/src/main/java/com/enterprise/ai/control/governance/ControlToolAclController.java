package com.enterprise.ai.control.governance;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/api/tool-acl")
@RequiredArgsConstructor
public class ControlToolAclController {

    private final ControlToolAclMapper mapper;

    @GetMapping
    public ResponseEntity<Page<ControlToolAclEntity>> page(@RequestParam(defaultValue = "1") int current,
                                                           @RequestParam(defaultValue = "20") int size,
                                                           @RequestParam(required = false) String roleCode,
                                                           @RequestParam(required = false) String targetKind) {
        LambdaQueryWrapper<ControlToolAclEntity> wrapper = new LambdaQueryWrapper<ControlToolAclEntity>()
                .eq(StringUtils.hasText(roleCode), ControlToolAclEntity::getRoleCode, roleCode)
                .eq(StringUtils.hasText(targetKind), ControlToolAclEntity::getTargetKind, upper(targetKind))
                .orderByDesc(ControlToolAclEntity::getId);
        return ResponseEntity.ok(mapper.selectPage(new Page<>(safePage(current), safeSize(size, 20, 500)), wrapper));
    }

    @GetMapping("/roles")
    public ResponseEntity<List<String>> roles() {
        List<String> roles = mapper.selectList(new LambdaQueryWrapper<ControlToolAclEntity>()
                        .orderByAsc(ControlToolAclEntity::getRoleCode))
                .stream()
                .map(ControlToolAclEntity::getRoleCode)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
        return ResponseEntity.ok(roles);
    }

    @PostMapping
    public ResponseEntity<ControlToolAclEntity> create(@RequestBody ControlToolAclEntity request) {
        ControlToolAclEntity entity = new ControlToolAclEntity();
        applyPayload(entity, request);
        LocalDateTime now = LocalDateTime.now();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        mapper.insert(entity);
        return ResponseEntity.ok(entity);
    }

    @PutMapping("/{id}")
    public ResponseEntity<ControlToolAclEntity> update(@PathVariable Long id,
                                                       @RequestBody ControlToolAclEntity request) {
        ControlToolAclEntity entity = mapper.selectById(id);
        if (entity == null) {
            return ResponseEntity.notFound().build();
        }
        applyPayload(entity, request);
        entity.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(entity);
        return ResponseEntity.ok(entity);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable Long id) {
        mapper.deleteById(id);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/{id}/toggle")
    public ResponseEntity<ControlToolAclEntity> toggle(@PathVariable Long id,
                                                       @RequestBody ToggleRequest request) {
        ControlToolAclEntity entity = mapper.selectById(id);
        if (entity == null) {
            return ResponseEntity.notFound().build();
        }
        entity.setEnabled(request == null || request.enabled() == null || request.enabled());
        entity.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(entity);
        return ResponseEntity.ok(entity);
    }

    @PostMapping("/batch")
    public ResponseEntity<Map<String, Object>> grantBatch(@RequestBody GrantBatchRequest request) {
        String roleCode = requireText(request == null ? null : request.roleCode(), "roleCode");
        String permission = permission(request.permission());
        List<ToolAclTargetRef> targets = request.targets() == null ? List.of() : request.targets();
        LocalDateTime now = LocalDateTime.now();
        int count = 0;
        for (ToolAclTargetRef target : targets) {
            String targetKind = kind(target == null ? null : target.kind());
            String targetName = requireText(target == null ? null : target.name(), "target.name");
            ControlToolAclEntity entity = mapper.selectOne(new LambdaQueryWrapper<ControlToolAclEntity>()
                    .eq(ControlToolAclEntity::getRoleCode, roleCode)
                    .eq(ControlToolAclEntity::getTargetKind, targetKind)
                    .eq(ControlToolAclEntity::getTargetName, targetName)
                    .last("limit 1"));
            if (entity == null) {
                entity = new ControlToolAclEntity();
                entity.setRoleCode(roleCode);
                entity.setTargetKind(targetKind);
                entity.setTargetName(targetName);
                entity.setCreatedAt(now);
            }
            entity.setPermission(permission);
            entity.setNote(trimToNull(request.note()));
            entity.setEnabled(true);
            entity.setUpdatedAt(now);
            if (entity.getId() == null) {
                mapper.insert(entity);
            } else {
                mapper.updateById(entity);
            }
            count++;
        }
        return ResponseEntity.ok(Map.of("ok", true, "count", count));
    }

    @PostMapping("/explain")
    public ResponseEntity<Map<String, String>> explain(@RequestBody ExplainRequest request) {
        List<String> roles = request == null || request.roles() == null ? List.of() : request.roles().stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .toList();
        List<ToolAclTargetRef> targets = request == null || request.targets() == null ? List.of() : request.targets();
        Map<String, String> result = new LinkedHashMap<>();
        if (roles.isEmpty()) {
            for (ToolAclTargetRef target : targets) {
                result.put(targetName(target), "SKIPPED");
            }
            return ResponseEntity.ok(result);
        }
        List<ControlToolAclEntity> rules = mapper.selectList(new LambdaQueryWrapper<ControlToolAclEntity>()
                .in(ControlToolAclEntity::getRoleCode, roles)
                .eq(ControlToolAclEntity::getEnabled, true));
        for (ToolAclTargetRef target : targets) {
            result.put(targetName(target), decide(roles, target, rules));
        }
        return ResponseEntity.ok(result);
    }

    private void applyPayload(ControlToolAclEntity entity, ControlToolAclEntity request) {
        if (request == null) {
            throw new IllegalArgumentException("tool acl request is required");
        }
        entity.setRoleCode(requireText(request.getRoleCode(), "roleCode"));
        entity.setProjectId(request.getProjectId());
        entity.setProjectCode(trimToNull(request.getProjectCode()));
        entity.setTargetKind(kind(request.getTargetKind()));
        entity.setTargetName(requireText(request.getTargetName(), "targetName"));
        entity.setPermission(permission(request.getPermission()));
        entity.setNote(trimToNull(request.getNote()));
        entity.setEnabled(request.getEnabled() == null || request.getEnabled());
    }

    private String decide(List<String> roles, ToolAclTargetRef target, List<ControlToolAclEntity> rules) {
        String targetKind = kind(target == null ? null : target.kind());
        String targetName = targetName(target);
        boolean allowed = false;
        for (ControlToolAclEntity rule : rules) {
            if (!roles.contains(rule.getRoleCode()) || !matches(rule, targetKind, targetName)) {
                continue;
            }
            if ("DENY".equalsIgnoreCase(rule.getPermission())) {
                return "DENY_EXPLICIT";
            }
            if ("ALLOW".equalsIgnoreCase(rule.getPermission())) {
                allowed = true;
            }
        }
        return allowed ? "ALLOW" : "DENY_NO_MATCH";
    }

    private boolean matches(ControlToolAclEntity rule, String targetKind, String targetName) {
        String ruleKind = upper(rule.getTargetKind());
        boolean kindMatches = Objects.equals(ruleKind, targetKind) || Objects.equals(ruleKind, "ALL");
        boolean nameMatches = Objects.equals(rule.getTargetName(), "*") || Objects.equals(rule.getTargetName(), targetName);
        return kindMatches && nameMatches;
    }

    private String targetName(ToolAclTargetRef target) {
        return requireText(target == null ? null : target.name(), "target.name");
    }

    private int safePage(int requested) {
        return Math.max(requested, 1);
    }

    private int safeSize(int requested, int fallback, int max) {
        int value = requested <= 0 ? fallback : requested;
        return Math.min(Math.max(value, 1), max);
    }

    private String kind(String value) {
        String normalized = StringUtils.hasText(value) ? upper(value) : "TOOL";
        if (!List.of("TOOL", "SKILL", "ALL").contains(normalized)) {
            throw new IllegalArgumentException("target kind is invalid");
        }
        return normalized;
    }

    private String permission(String value) {
        String normalized = StringUtils.hasText(value) ? upper(value) : "ALLOW";
        if (!List.of("ALLOW", "DENY").contains(normalized)) {
            throw new IllegalArgumentException("permission is invalid");
        }
        return normalized;
    }

    private String upper(String value) {
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private String requireText(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    public record ToggleRequest(Boolean enabled) {
    }

    public record GrantBatchRequest(String roleCode, String permission, List<ToolAclTargetRef> targets, String note) {
    }

    public record ExplainRequest(List<String> roles, List<ToolAclTargetRef> targets) {
    }

    public record ToolAclTargetRef(String kind, String name) {
    }
}
