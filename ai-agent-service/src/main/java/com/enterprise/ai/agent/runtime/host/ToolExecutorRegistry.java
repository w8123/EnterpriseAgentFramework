package com.enterprise.ai.agent.runtime.host;


import com.enterprise.ai.agent.runtime.*;
import com.enterprise.ai.agent.capability.ToolAssetExecutor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class ToolExecutorRegistry {

    private final Map<String, ToolAssetExecutor> executors = new LinkedHashMap<>();

    public ToolExecutorRegistry(List<ToolAssetExecutor> executors) {
        if (executors != null) {
            executors.forEach(this::register);
        }
    }

    public void register(ToolAssetExecutor executor) {
        if (executor == null || executor.executorType() == null) {
            return;
        }
        executors.put(normalize(executor.executorType()), executor);
    }

    public ToolAssetExecutor get(String executorType) {
        ToolAssetExecutor executor = executors.get(normalize(executorType));
        if (executor == null) {
            throw new IllegalArgumentException("Unsupported tool executor type: " + executorType);
        }
        return executor;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
