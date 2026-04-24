package com.enterprise.ai.agent.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.enterprise.ai.agent.acl.ToolAclDecision;
import com.enterprise.ai.agent.acl.ToolAclEntity;
import com.enterprise.ai.agent.acl.ToolAclService;
import com.enterprise.ai.agent.acl.ToolAclService.ToolAclTargetRef;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Phase 3.1 Tool ACL 的管理端 REST 入口。
 * <p>
 * 语义和前端 {@code api/toolAcl.ts} 一一对应：
 * <ul>
 *   <li>{@code GET /api/tool-acl} — 分页查询规则列表；</li>
 *   <li>{@code GET /api/tool-acl/roles} — 角色去重列表，供前端左栏；</li>
 *   <li>{@code POST /api/tool-acl} — 新建规则；</li>
 *   <li>{@code PUT /api/tool-acl/{id}} — 修改规则；</li>
 *   <li>{@code DELETE /api/tool-acl/{id}} — 删除规则；</li>
 *   <li>{@code POST /api/tool-acl/{id}/toggle} — 启停规则；</li>
 *   <li>{@code POST /api/tool-acl/batch} — 按 role 批量授权多个 target；</li>
 *   <li>{@code POST /api/tool-acl/explain} — 诊断：给定 roles + targets 查看决策结果。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/tool-acl")
@RequiredArgsConstructor
public class ToolAclController {

    private final ToolAclService service;

    @GetMapping
    public ResponseEntity<Page<ToolAclEntity>> page(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String roleCode,
            @RequestParam(required = false) String targetKind) {
        return ResponseEntity.ok(service.page(current, size, roleCode, targetKind));
    }

    @GetMapping("/roles")
    public ResponseEntity<List<String>> roles() {
        return ResponseEntity.ok(service.listRoles());
    }

    @PostMapping
    public ResponseEntity<ToolAclEntity> create(@RequestBody ToolAclEntity body) {
        return ResponseEntity.ok(service.create(body));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ToolAclEntity> update(@PathVariable Long id, @RequestBody ToolAclEntity body) {
        return ResponseEntity.ok(service.update(id, body));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/{id}/toggle")
    public ResponseEntity<ToolAclEntity> toggle(@PathVariable Long id, @RequestBody ToggleRequest req) {
        return ResponseEntity.ok(service.toggle(id, req.enabled()));
    }

    @PostMapping("/batch")
    public ResponseEntity<Map<String, Object>> grantBatch(@RequestBody GrantBatchRequest req) {
        int n = service.grantBatch(req.roleCode(), req.permission(), req.targets(), req.note());
        return ResponseEntity.ok(Map.of("ok", true, "count", n));
    }

    @PostMapping("/explain")
    public ResponseEntity<Map<String, ToolAclDecision>> explain(@RequestBody ExplainRequest req) {
        return ResponseEntity.ok(service.explain(req.roles(), req.targets()));
    }

    public record ToggleRequest(boolean enabled) {}

    public record GrantBatchRequest(
            String roleCode,
            String permission,
            List<ToolAclTargetRef> targets,
            String note) {}

    public record ExplainRequest(List<String> roles, List<ToolAclTargetRef> targets) {}
}
