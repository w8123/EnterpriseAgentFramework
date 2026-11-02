package com.enterprise.ai.runtime.supervisor;

import com.enterprise.ai.common.capability.WorkflowReadOnlyTrialPolicy;
import com.enterprise.ai.runtime.agent.RuntimeAgentWorkflowToolSnapshot;
import com.enterprise.ai.runtime.agent.RuntimeResolvedWorkflowTarget;
import com.enterprise.ai.runtime.workflow.RuntimeCapabilityContractPins;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/** Classifies repeatable reads from the pinned graph and its current owning contracts, never the model. */
@Component
public final class SupervisorRepeatableReadPolicy {
    private final ObjectMapper objectMapper;
    private final RuntimeCapabilityContractPins pins;

    public SupervisorRepeatableReadPolicy(ObjectMapper objectMapper, RuntimeCapabilityContractPins pins) {
        this.objectMapper = objectMapper;
        this.pins = pins;
    }

    public boolean isRepeatable(RuntimeAgentWorkflowToolSnapshot tool, RuntimeResolvedWorkflowTarget target) {
        if (tool == null || !Boolean.TRUE.equals(tool.getReadOnly())
                || !("READ".equalsIgnoreCase(tool.getRiskLevel()) || "READ_ONLY".equalsIgnoreCase(tool.getRiskLevel()))
                || target == null || target.version() == null) return false;
        String graph = target.version().getGraphSpecSnapshotJson();
        if (graph == null || graph.isBlank()) return false;
        try {
            var plan = WorkflowReadOnlyTrialPolicy.plan(objectMapper, graph);
            if (plan.targets().isEmpty() || plan.targets().stream()
                    .anyMatch(t -> !WorkflowReadOnlyTrialPolicy.BUSINESS_METHOD.equals(t.assetType()))) return false;
            JsonNode nodes = objectMapper.readTree(graph).path("nodes");
            for (JsonNode node : nodes) {
                String type = node.path("type").asText("").trim().toUpperCase(java.util.Locale.ROOT);
                if (!"TOOL".equals(type) && !WorkflowReadOnlyTrialPolicy.PURE_NODE_TYPES.contains(type)) return false;
            }
            // Re-read owner facts each time: a formerly read-only source may have changed.
            return "READ".equals(pins.businessMethodRiskFloor(graph));
        } catch (RuntimeException | java.io.IOException invalidOrUnavailable) {
            return false;
        }
    }
}
