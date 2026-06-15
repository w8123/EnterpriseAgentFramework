package com.enterprise.ai.agent.controller;

import com.enterprise.ai.agent.workflow.AgentEntryEntity;
import com.enterprise.ai.agent.workflow.AgentEntryService;
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

@RestController
@RequestMapping("/api/agents")
@RequiredArgsConstructor
public class AgentEntryController {

    private final AgentEntryService service;

    @GetMapping
    public ResponseEntity<List<AgentEntryEntity>> list(@RequestParam(required = false) Long projectId,
                                                       @RequestParam(required = false) String projectCode,
                                                       @RequestParam(required = false) String agentKind) {
        return ResponseEntity.ok(service.list(projectId, projectCode, agentKind));
    }

    @PostMapping
    public ResponseEntity<AgentEntryEntity> create(@RequestBody AgentEntryEntity request) {
        return ResponseEntity.ok(service.create(request));
    }

    @GetMapping("/{id}")
    public ResponseEntity<AgentEntryEntity> get(@PathVariable String id) {
        return service.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}")
    public ResponseEntity<AgentEntryEntity> update(@PathVariable String id,
                                                   @RequestBody AgentEntryEntity request) {
        return ResponseEntity.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        return service.delete(id) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }
}
