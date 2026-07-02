package com.enterprise.ai.runtime.compat;

import com.enterprise.ai.runtime.agent.RuntimeAgentEntryService;
import com.enterprise.ai.runtime.agent.RuntimeAgentEntryView;
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
public class RuntimeAgentEntryCompatibilityController {

    private final RuntimeAgentEntryService agentEntryService;

    @GetMapping("/api/agents")
    public ResponseEntity<List<RuntimeAgentEntryView>> list(@RequestParam(required = false) Long projectId,
                                                            @RequestParam(required = false) String projectCode,
                                                            @RequestParam(required = false) String agentKind) {
        return ResponseEntity.ok(agentEntryService.list(projectId, projectCode, agentKind));
    }

    @PostMapping("/api/agents")
    public ResponseEntity<RuntimeAgentEntryView> create(@RequestBody RuntimeAgentEntryView request) {
        return ResponseEntity.ok(agentEntryService.create(request));
    }

    @GetMapping("/api/agents/{id}")
    public ResponseEntity<RuntimeAgentEntryView> get(@PathVariable String id) {
        return agentEntryService.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/api/agents/{id}")
    public ResponseEntity<RuntimeAgentEntryView> update(@PathVariable String id,
                                                        @RequestBody RuntimeAgentEntryView request) {
        return ResponseEntity.ok(agentEntryService.update(id, request));
    }

    @DeleteMapping("/api/agents/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        return agentEntryService.delete(id) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }
}
