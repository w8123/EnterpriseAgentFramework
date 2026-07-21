package com.enterprise.ai.runtime.agent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Request-scoped execution snapshot: Agent + ACTIVE config + enabled tools + resolved Workflow targets.
 */
public record RuntimeAgentExecutionContext(
        RuntimeAgentExecutionView agent,
        RuntimeAgentConfigVersionEntity config,
        List<RuntimeAgentWorkflowToolEntity> tools,
        List<RuntimeResolvedWorkflowTarget> resolvedTargets,
        ResolveTimings timings) {

    public RuntimeAgentView agentView() {
        String runtimeType = config == null ? null : config.getRuntimeType();
        int toolCount = tools == null ? 0 : tools.size();
        return agent.toRuntimeAgentView(toolCount, runtimeType);
    }

    public Map<String, Object> timingMetadata() {
        Map<String, Object> meta = new LinkedHashMap<>();
        if (timings == null) {
            return meta;
        }
        meta.put("runtime.agentResolveMs", timings.agentResolveMs());
        meta.put("runtime.configResolveMs", timings.configResolveMs());
        meta.put("runtime.toolResolveMs", timings.toolResolveMs());
        meta.put("runtime.workflowTargetResolveMs", timings.workflowTargetResolveMs());
        meta.put("runtime.resolveTotalMs", timings.totalMs());
        return meta;
    }

    public record ResolveTimings(
            long agentResolveMs,
            long configResolveMs,
            long toolResolveMs,
            long workflowTargetResolveMs,
            long totalMs) {
    }
}
