package com.enterprise.ai.agent.runtime;

import com.enterprise.ai.agent.capability.CapabilityAssetService;
import com.enterprise.ai.agent.capability.ToolAssetEntity;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class ToolRuntimeExecutor {

    private final CapabilityAssetService assetService;
    private final ToolExecutorRegistry executorRegistry;

    public ToolRuntimeExecutor(CapabilityAssetService assetService, ToolExecutorRegistry executorRegistry) {
        this.assetService = assetService;
        this.executorRegistry = executorRegistry;
    }

    public ToolRuntimeResult execute(ToolRuntimeRequest request) {
        String qualifiedName = request == null ? null : request.qualifiedName();
        ToolAssetEntity tool = assetService.findToolByQualifiedName(qualifiedName)
                .orElseThrow(() -> new IllegalArgumentException("tool asset not found: " + qualifiedName));
        if (!Boolean.TRUE.equals(tool.getEnabled())) {
            return ToolRuntimeResult.failure(qualifiedName, "tool asset disabled");
        }
        ToolRuntimeRequest safeRequest = ToolRuntimeRequest.builder()
                .qualifiedName(qualifiedName)
                .args(request.args() == null ? Map.of() : request.args())
                .context(request.context() == null ? Map.of() : request.context())
                .build();
        return executorRegistry.get(tool.getExecutorType()).execute(tool, safeRequest);
    }
}
