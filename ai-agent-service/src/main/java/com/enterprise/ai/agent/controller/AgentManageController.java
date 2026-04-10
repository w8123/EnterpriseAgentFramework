package com.enterprise.ai.agent.controller;

import com.enterprise.ai.agent.agent.AgentDefinition;
import com.enterprise.ai.agent.agent.AgentDefinitionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Agent 配置管理 API
 * <p>
 * 提供 Agent 定义的 CRUD 接口，用于动态管理智能体的配置。
 */
@Slf4j
@RestController
@RequestMapping("/api/agent/definitions")
@RequiredArgsConstructor
public class AgentManageController {

    private final AgentDefinitionService definitionService;

    @GetMapping
    public ResponseEntity<List<AgentDefinition>> list() {
        return ResponseEntity.ok(definitionService.list());
    }

    @GetMapping("/{id}")
    public ResponseEntity<AgentDefinition> get(@PathVariable String id) {
        return definitionService.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<AgentDefinition> create(@RequestBody AgentDefinition definition) {
        AgentDefinition created = definitionService.create(definition);
        return ResponseEntity.ok(created);
    }

    @PutMapping("/{id}")
    public ResponseEntity<AgentDefinition> update(@PathVariable String id,
                                                  @RequestBody AgentDefinition definition) {
        try {
            AgentDefinition updated = definitionService.update(id, definition);
            return ResponseEntity.ok(updated);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        boolean deleted = definitionService.delete(id);
        return deleted ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }
}
