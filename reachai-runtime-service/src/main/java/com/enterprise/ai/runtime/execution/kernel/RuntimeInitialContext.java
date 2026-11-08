package com.enterprise.ai.runtime.execution.kernel;

import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.runtime.execution.context.WorkflowVariableNamespaces;
import com.enterprise.ai.runtime.identity.WorkflowExecutionIdentity;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/** Builds the variables a run starts with: inputs, var, sys and the workflow's env. */
final class RuntimeInitialContext {

    private RuntimeInitialContext() {
    }

    static Map<String, Object> initialContext(Map<String, Object> request, RuntimeNodeValueResolver nodeValueResolver) {
        Map<String, Object> context = new LinkedHashMap<>(request);
        String input = nodeValueResolver.userInputText(request);
        context.putIfAbsent("input", input);
        context.putIfAbsent("userInput", input);
        context.putIfAbsent("message", input);
        context.putIfAbsent("lastOutput", input);
        context.putIfAbsent("previousOutput", input);
        context.putIfAbsent(WorkflowVariableNamespaces.VAR_ROOT, new LinkedHashMap<String, Object>());
        if (!(context.get(WorkflowVariableNamespaces.SYS_ROOT) instanceof Map<?, ?>)) {
            Map<String, Object> sys = new LinkedHashMap<>();
            putIfPresent(sys, "userId", firstPresent(request.get("userId"), request.get("externalUserId")));
            putIfPresent(sys, "tenantId", request.get("tenantId"));
            putIfPresent(sys, "roles", request.get("roles"));
            putIfPresent(sys, "projectCode", request.get("projectCode"));
            putIfPresent(sys, "projectId", request.get("projectId"));
            putIfPresent(sys, "sessionId", request.get("sessionId"));
            context.put(WorkflowVariableNamespaces.SYS_ROOT, sys);
        }
        return context;
    }

    /**
     * Workflow env values come only from the published GraphSpec; callers can't inject or override them.
     * sys.now / sys.today / sys.channel / sys.runId are added when the caller didn't provide them.
     */
    static void applyWorkflowEnvAndSystemValues(Map<String, Object> context,
                                                GraphSpec graph,
                                                WorkflowExecutionIdentity identity) {
        Map<String, Object> env = new LinkedHashMap<>();
        if (graph != null && graph.getEnv() != null) {
            graph.getEnv().forEach((key, value) -> {
                if (StringUtils.hasText(key) && value != null) {
                    env.put(key.trim(), value instanceof String ? value : String.valueOf(value));
                }
            });
        }
        context.put(WorkflowVariableNamespaces.ENV_ROOT, env);
        Map<String, Object> existingSys = mapValue(context.get(WorkflowVariableNamespaces.SYS_ROOT));
        Map<String, Object> sys = existingSys == null ? new LinkedHashMap<>() : new LinkedHashMap<>(existingSys);
        java.time.ZonedDateTime now = java.time.ZonedDateTime.now();
        sys.putIfAbsent("now", now.toOffsetDateTime().withNano(0).toString());
        sys.putIfAbsent("today", now.toLocalDate().toString());
        if (identity != null && identity.source() != null) {
            sys.putIfAbsent("channel", identity.source().name());
        }
        if (!sys.containsKey("runId")) {
            putIfPresent(sys, "runId", firstPresent(context.get("runId"),
                    firstPresent(context.get("executionId"), context.get("traceId"))));
        }
        context.put(WorkflowVariableNamespaces.SYS_ROOT, sys);
    }

    private static void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private static Object firstPresent(Object first, Object fallback) {
        return first != null ? first : fallback;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapValue(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }
}
