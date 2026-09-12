package com.enterprise.ai.runtime.execution.kernel;

import com.enterprise.ai.runtime.execution.policy.RuntimeEvalExecutionContext;
import com.enterprise.ai.runtime.identity.WorkflowExecutionIdentity;

import java.util.Map;

/** Resolves server-owned identity and evaluation markers from the live execution context. */
final class RuntimeTrustedExecutionContexts {

    static final String IDENTITY_CONTEXT_KEY = "__workflowExecutionIdentity";
    static final String EVAL_CONTEXT_KEY = "__runtimeEvalExecutionContext";

    private RuntimeTrustedExecutionContexts() {
    }

    static WorkflowExecutionIdentity identity(Map<String, Object> context) {
        if (context == null) {
            return WorkflowExecutionIdentity.untrustedDebug();
        }
        Object raw = context.get(IDENTITY_CONTEXT_KEY);
        if (raw instanceof WorkflowExecutionIdentity identity) {
            return identity;
        }
        // Runtime-owned snapshots may rehydrate identity as a Map. Trust flags are recomputed from
        // source and never accepted from client-provided projectTrusted/userTrusted values.
        if (raw instanceof Map<?, ?> map) {
            return WorkflowExecutionIdentity.restoreFromContextMap(map);
        }
        return WorkflowExecutionIdentity.untrustedDebug();
    }

    static RuntimeEvalExecutionContext evaluation(Map<String, Object> context) {
        if (context == null) {
            return RuntimeEvalExecutionContext.none();
        }
        Object raw = context.get(EVAL_CONTEXT_KEY);
        return raw instanceof RuntimeEvalExecutionContext evaluation
                ? evaluation : RuntimeEvalExecutionContext.none();
    }
}
