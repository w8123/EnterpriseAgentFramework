package com.enterprise.ai.runtime.compat;

import com.enterprise.ai.runtime.registry.RuntimeAgentGraphSyncContracts.AgentGraphSyncRequest;
import com.enterprise.ai.runtime.registry.RuntimeAgentGraphSyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/registry")
@RequiredArgsConstructor
public class RuntimeRegistryOperationsCompatibilityController {

    private final RuntimeAgentGraphSyncService syncService;

    @PostMapping("/projects/{projectCode}/agent-graphs/sync")
    public ResponseEntity<?> syncAgentGraphs(@PathVariable String projectCode,
                                             @RequestBody(required = false) AgentGraphSyncRequest request) {
        try {
            return ResponseEntity.ok(syncService.sync(projectCode, request));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new ApiErrorResponse(ex.getMessage()));
        }
    }

    public record ApiErrorResponse(String message) {
    }
}
