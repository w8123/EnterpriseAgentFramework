package com.enterprise.ai.runtime.agent;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class RuntimeAgentController {

    private final RuntimeAgentService agentService;
    private final RuntimeAgentStatisticsService statisticsService;

    @GetMapping("/api/agents")
    public ResponseEntity<List<RuntimeAgentView>> list(@RequestParam(required = false) Long projectId,
                                                        @RequestParam(required = false) String projectCode) {
        return ResponseEntity.ok(agentService.list(projectId, projectCode));
    }

    @GetMapping("/api/agents/statistics")
    public ResponseEntity<RuntimeAgentStatisticsView> statistics(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String projectCode) {
        return ResponseEntity.ok(statisticsService.statistics(projectId, projectCode));
    }

    @PostMapping("/api/agents")
    public ResponseEntity<RuntimeAgentView> create(@RequestBody RuntimeAgentIdentityRequest request) {
        return ResponseEntity.ok(agentService.create(request));
    }

    @GetMapping("/api/agents/{id}")
    public ResponseEntity<RuntimeAgentView> get(@PathVariable String id) {
        return agentService.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/api/agents/{id}")
    public ResponseEntity<RuntimeAgentView> update(@PathVariable String id,
                                                    @RequestBody RuntimeAgentIdentityRequest request) {
        return ResponseEntity.ok(agentService.update(id, request));
    }

    @DeleteMapping("/api/agents/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        return agentService.delete(id) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }
}
