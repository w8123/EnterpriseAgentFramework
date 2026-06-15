package com.enterprise.ai.agent.controller;

import com.enterprise.ai.agent.agent.AgentDefinition;
import com.enterprise.ai.agent.runtime.LangGraph4jRuntimeAdapter;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/agent/studio")
@RequiredArgsConstructor
@Deprecated
public class AgentStudioDebugController {

    private final LangGraph4jRuntimeAdapter langGraph4jRuntimeAdapter;

    @PostMapping("/debug-node")
    public ResponseEntity<LangGraph4jRuntimeAdapter.NodeDebugResult> debugNode(@RequestBody NodeDebugRequest request) {
        if (request == null || request.getAgentDefinition() == null || !StringUtils.hasText(request.getNodeId())) {
            throw new IllegalArgumentException("agentDefinition and nodeId are required");
        }
        return ResponseEntity.ok(langGraph4jRuntimeAdapter.debugNode(
                request.getAgentDefinition(),
                request.getNodeId(),
                request.getMessage(),
                request.getState()));
    }

    @PostMapping("/debug-run")
    public ResponseEntity<LangGraph4jRuntimeAdapter.WorkflowDebugRunResult> debugRun(@RequestBody DebugRunRequest request) {
        if (request == null || request.getAgentDefinition() == null) {
            throw new IllegalArgumentException("agentDefinition is required");
        }
        return ResponseEntity.ok(langGraph4jRuntimeAdapter.debugRun(
                request.getAgentDefinition(),
                request.getMessage(),
                request.getInputParams(),
                request.getDebugOptions()));
    }

    @Data
    public static class NodeDebugRequest {
        private AgentDefinition agentDefinition;
        private String nodeId;
        private String message;
        private Map<String, Object> state;
    }

    @Data
    public static class DebugRunRequest {
        private AgentDefinition agentDefinition;
        private String message;
        private Map<String, Object> inputParams;
        private Map<String, Object> debugOptions;
    }
}
