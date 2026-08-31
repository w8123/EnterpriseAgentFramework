package com.enterprise.ai.runtime.execution.kernel;

import com.enterprise.ai.agent.graph.AgentGraphNodeType;
import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionResult;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Immutable Runtime handler registry.
 *
 * <p>This is the executable truth source inside the kernel. Product exposure remains owned by
 * {@code RuntimeWorkflowNodeCapabilityRegistry}; its conformance test must keep both catalogs in
 * lockstep.</p>
 */
public final class RuntimeNodeHandlerRegistry {

    private final Map<String, RuntimeNodeHandler> handlers;

    private RuntimeNodeHandlerRegistry(Map<String, RuntimeNodeHandler> handlers, Set<String> expectedTypes) {
        this.handlers = Map.copyOf(handlers);
        Set<String> expected = normalizeTypes(expectedTypes);
        if (!this.handlers.keySet().equals(expected)) {
            Set<String> missing = new LinkedHashSet<>(expected);
            missing.removeAll(this.handlers.keySet());
            Set<String> unexpected = new LinkedHashSet<>(this.handlers.keySet());
            unexpected.removeAll(expected);
            throw new IllegalStateException(
                    "Runtime node handler registry mismatch; missing=" + missing + ", unexpected=" + unexpected);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public Optional<RuntimeNodeHandler> find(String rawNodeType) {
        return Optional.ofNullable(handlers.get(normalize(rawNodeType)));
    }

    public Set<String> handledNodeTypes() {
        return handlers.keySet();
    }

    private static Set<String> normalizeTypes(Set<String> rawTypes) {
        Set<String> normalized = new LinkedHashSet<>();
        if (rawTypes != null) {
            rawTypes.forEach(type -> {
                String value = normalize(type);
                if (StringUtils.hasText(value)) {
                    normalized.add(value);
                }
            });
        }
        return Set.copyOf(normalized);
    }

    private static String normalize(String rawNodeType) {
        return AgentGraphNodeType.normalize(rawNodeType);
    }

    @FunctionalInterface
    public interface NodeExecution {
        RuntimeGraphSpecExecutionResult execute(GraphSpec.Node node, RuntimeNodeExecutionContext context);
    }

    public static final class Builder {
        private final Map<String, RuntimeNodeHandler> handlers = new LinkedHashMap<>();

        public Builder register(String rawNodeType, NodeExecution execution) {
            String nodeType = normalize(rawNodeType);
            if (!StringUtils.hasText(nodeType)) {
                throw new IllegalArgumentException("Runtime node handler type is required");
            }
            if (execution == null) {
                throw new IllegalArgumentException("Runtime node handler execution is required: " + nodeType);
            }
            RuntimeNodeHandler handler = new RuntimeNodeHandler() {
                @Override
                public String nodeType() {
                    return nodeType;
                }

                @Override
                public RuntimeGraphSpecExecutionResult execute(GraphSpec.Node node,
                                                               RuntimeNodeExecutionContext context) {
                    return execution.execute(node, context);
                }
            };
            RuntimeNodeHandler existing = handlers.putIfAbsent(nodeType, handler);
            if (existing != null) {
                throw new IllegalStateException("Duplicate Runtime node handler: " + nodeType);
            }
            return this;
        }

        public RuntimeNodeHandlerRegistry build(Set<String> expectedTypes) {
            return new RuntimeNodeHandlerRegistry(new LinkedHashMap<>(handlers), expectedTypes);
        }
    }
}
