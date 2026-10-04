package com.enterprise.ai.runtime.execution.kernel;

import com.enterprise.ai.agent.graph.GraphSpec;
import java.util.LinkedHashMap;
import java.util.Map;

/** Shared pure API mapping semantics for execution and safe pre-dispatch argument presentation. */
public final class RuntimeHttpApiInputBindings {
    private RuntimeHttpApiInputBindings() { }

    public static Map<String, Object> resolve(GraphSpec.Node node, Map<String, Object> context) {
        Map<String, Object> config = node.getConfig() == null ? Map.of() : node.getConfig();
        Object raw = config.get("inputMapping");
        if (!(raw instanceof Map<?, ?>)) raw = config.get("args");
        Map<String, Object> input = new LinkedHashMap<>();
        var resolver = new RuntimeNodeValueResolver();
        if (raw instanceof Map<?, ?> mapping) mapping.forEach((key, expression) -> {
            if (key == null) return;
            var value = resolver.renderPresentInputValue(expression, context);
            if (value.present()) input.put(String.valueOf(key), value.value());
        });
        return input; // No implicit lastOutput and no source defaults.
    }
}
