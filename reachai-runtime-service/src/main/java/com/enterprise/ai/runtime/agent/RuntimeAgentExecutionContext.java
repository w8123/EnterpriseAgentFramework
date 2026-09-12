package com.enterprise.ai.runtime.agent;


import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Request-scoped execution snapshot: Agent + ACTIVE config + enabled tools + resolved Workflow targets.
 */
public record RuntimeAgentExecutionContext(
        RuntimeAgentExecutionView agent,
        RuntimeAgentConfigSnapshot config,
        List<RuntimeAgentWorkflowToolSnapshot> tools,
        List<RuntimeAgentSkillBindingSnapshot> skills,
        List<RuntimeAgentRemoteBindingView> remoteAgents,
        List<RuntimeResolvedWorkflowTarget> resolvedTargets,
        ResolveTimings timings) {

    public RuntimeAgentExecutionContext {
        tools = tools == null ? List.of() : List.copyOf(tools);
        skills = skills == null ? List.of() : List.copyOf(skills);
        remoteAgents = remoteAgents == null ? List.of() : List.copyOf(remoteAgents);
        resolvedTargets = resolvedTargets == null ? List.of() : List.copyOf(resolvedTargets);
    }

    /** Source-compatible constructor for executions created before Skill bindings existed. */
    public RuntimeAgentExecutionContext(
            RuntimeAgentExecutionView agent,
            RuntimeAgentConfigSnapshot config,
            List<RuntimeAgentWorkflowToolSnapshot> tools,
            List<RuntimeAgentSkillBindingSnapshot> skills,
            List<RuntimeResolvedWorkflowTarget> resolvedTargets,
            ResolveTimings timings) {
        this(agent, config, tools, skills, List.of(), resolvedTargets, timings);
    }

    public RuntimeAgentExecutionContext(
            RuntimeAgentExecutionView agent,
            RuntimeAgentConfigSnapshot config,
            List<RuntimeAgentWorkflowToolSnapshot> tools,
            List<RuntimeResolvedWorkflowTarget> resolvedTargets,
            ResolveTimings timings) {
        this(agent, config, tools, List.of(), List.of(), resolvedTargets, timings);
    }

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
        meta.put("runtime.skillResolveMs", timings.skillResolveMs());
        meta.put("runtime.a2aBindingResolveMs", timings.a2aBindingResolveMs());
        meta.put("runtime.workflowTargetResolveMs", timings.workflowTargetResolveMs());
        meta.put("runtime.resolveTotalMs", timings.totalMs());
        return meta;
    }

    public record ResolveTimings(
            long agentResolveMs,
            long configResolveMs,
            long toolResolveMs,
            long skillResolveMs,
            long a2aBindingResolveMs,
            long workflowTargetResolveMs,
            long totalMs) {

        public ResolveTimings(
                long agentResolveMs,
                long configResolveMs,
                long toolResolveMs,
                long skillResolveMs,
                long workflowTargetResolveMs,
                long totalMs) {
            this(agentResolveMs, configResolveMs, toolResolveMs, skillResolveMs, 0L,
                    workflowTargetResolveMs, totalMs);
        }

        public ResolveTimings(
                long agentResolveMs,
                long configResolveMs,
                long toolResolveMs,
                long workflowTargetResolveMs,
                long totalMs) {
            this(agentResolveMs, configResolveMs, toolResolveMs, 0L, 0L,
                    workflowTargetResolveMs, totalMs);
        }
    }
}
