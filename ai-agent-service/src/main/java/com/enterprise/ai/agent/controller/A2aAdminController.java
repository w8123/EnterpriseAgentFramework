package com.enterprise.ai.agent.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.enterprise.ai.agent.a2a.A2aCallLogEntity;
import com.enterprise.ai.agent.a2a.A2aCallLogService;
import com.enterprise.ai.agent.a2a.A2aEndpointEntity;
import com.enterprise.ai.agent.a2a.A2aEndpointService;
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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A2A 适配的管理端 API：管理 a2a_endpoint（绑定 Agent + AgentCard 编辑） + 调用流水查询。
 */
@RestController
@RequestMapping("/api/admin/a2a")
@RequiredArgsConstructor
public class A2aAdminController {

    private final A2aEndpointService endpointService;
    private final A2aCallLogService callLogService;

    @GetMapping("/endpoints")
    public ResponseEntity<Page<A2aEndpointEntity>> listEndpoints(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String agentKey,
            @RequestParam(required = false) Boolean enabled) {
        return ResponseEntity.ok(endpointService.page(pageNum, pageSize, agentKey, enabled));
    }

    @GetMapping("/endpoints/{id}")
    public ResponseEntity<?> getEndpoint(@PathVariable Long id) {
        return endpointService.findById(id)
                .<ResponseEntity<?>>map(en -> {
                    Map<String, Object> view = new LinkedHashMap<>();
                    view.put("entity", en);
                    view.put("card", endpointService.parseCard(en));
                    return ResponseEntity.ok(view);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * 把指定 Agent 暴露为 A2A endpoint（或更新已有 endpoint 的 AgentCard）。
     */
    @PostMapping("/endpoints")
    public ResponseEntity<A2aEndpointEntity> upsertEndpoint(@RequestBody Map<String, Object> body) {
        String agentId = (String) body.get("agentId");
        if (agentId == null || agentId.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> cardOverrides = (Map<String, Object>) body.get("card");
        Boolean enabled = body.get("enabled") == null ? null : Boolean.valueOf(String.valueOf(body.get("enabled")));
        return ResponseEntity.ok(endpointService.upsertForAgent(agentId, cardOverrides, enabled));
    }

    @PutMapping("/endpoints/{id}/enabled")
    public ResponseEntity<Void> setEnabled(@PathVariable Long id, @RequestParam boolean enabled) {
        endpointService.setEnabled(id, enabled);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/endpoints/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        endpointService.delete(id);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/call-logs")
    public ResponseEntity<Page<A2aCallLogEntity>> listLogs(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String agentKey,
            @RequestParam(required = false) String method,
            @RequestParam(required = false) Boolean success) {
        return ResponseEntity.ok(callLogService.page(pageNum, pageSize, agentKey, method, success));
    }
}
