package com.enterprise.ai.agent.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.enterprise.ai.agent.mcp.McpCallLogEntity;
import com.enterprise.ai.agent.mcp.McpCallLogMapper;
import com.enterprise.ai.agent.mcp.McpClientEntity;
import com.enterprise.ai.agent.mcp.McpClientService;
import com.enterprise.ai.agent.mcp.McpVisibilityEntity;
import com.enterprise.ai.agent.mcp.McpVisibilityService;
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

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 管理端：Client / Visibility / CallLog。
 */
@RestController
@RequestMapping("/api/mcp")
@RequiredArgsConstructor
public class McpAdminController {

    private final McpClientService clientService;
    private final McpVisibilityService visibilityService;
    private final McpCallLogMapper callLogMapper;

    // ===== Client ==========================================================
    @GetMapping("/clients")
    public ResponseEntity<List<McpClientEntity>> listClients() {
        return ResponseEntity.ok(clientService.listAll());
    }

    @PostMapping("/clients")
    public ResponseEntity<Map<String, Object>> createClient(@RequestBody CreateClientRequest req) {
        McpClientService.CreateResult r = clientService.create(
                req.name(),
                req.roles(),
                req.toolWhitelist(),
                req.expiresAt());
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id", r.id());
        resp.put("plaintextApiKey", r.plaintextApiKey());
        resp.put("client", r.client());
        resp.put("note", "API Key 仅显示一次，请立即复制保存。");
        return ResponseEntity.ok(resp);
    }

    @PutMapping("/clients/{id}")
    public ResponseEntity<McpClientEntity> updateClient(@PathVariable Long id, @RequestBody UpdateClientRequest req) {
        return ResponseEntity.ok(clientService.update(id, req.name(), req.roles(),
                req.toolWhitelist(), req.enabled(), req.expiresAt()));
    }

    @DeleteMapping("/clients/{id}")
    public ResponseEntity<Map<String, Object>> deleteClient(@PathVariable Long id) {
        clientService.delete(id);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ===== Visibility ======================================================
    @GetMapping("/visibility")
    public ResponseEntity<List<McpVisibilityEntity>> listVisibility() {
        return ResponseEntity.ok(visibilityService.listAll());
    }

    @PostMapping("/visibility")
    public ResponseEntity<McpVisibilityEntity> setVisibility(@RequestBody SetVisibilityRequest req) {
        return ResponseEntity.ok(visibilityService.setExposed(req.kind(), req.name(),
                req.exposed() != null && req.exposed(), req.note()));
    }

    // ===== Call Log ========================================================
    @GetMapping("/call-logs")
    public ResponseEntity<Page<McpCallLogEntity>> pageLogs(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) Long clientId,
            @RequestParam(required = false) String method,
            @RequestParam(required = false) Boolean success,
            @RequestParam(defaultValue = "7") int days) {
        LambdaQueryWrapper<McpCallLogEntity> w = new LambdaQueryWrapper<>();
        if (clientId != null) w.eq(McpCallLogEntity::getClientId, clientId);
        if (method != null && !method.isBlank()) w.eq(McpCallLogEntity::getMethod, method);
        if (success != null) w.eq(McpCallLogEntity::getSuccess, success);
        if (days > 0) w.ge(McpCallLogEntity::getCreatedAt, LocalDateTime.now().minusDays(days));
        w.orderByDesc(McpCallLogEntity::getCreatedAt);
        return ResponseEntity.ok(callLogMapper.selectPage(new Page<>(current, size), w));
    }

    public record CreateClientRequest(String name, List<String> roles, List<String> toolWhitelist, LocalDateTime expiresAt) {}

    public record UpdateClientRequest(String name, List<String> roles, List<String> toolWhitelist,
                                       Boolean enabled, LocalDateTime expiresAt) {}

    public record SetVisibilityRequest(String kind, String name, Boolean exposed, String note) {}
}
