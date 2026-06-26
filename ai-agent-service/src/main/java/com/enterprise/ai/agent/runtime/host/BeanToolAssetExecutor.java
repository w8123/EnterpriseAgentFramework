package com.enterprise.ai.agent.runtime.host;


import com.enterprise.ai.agent.runtime.*;
import com.enterprise.ai.agent.capability.ToolAssetEntity;
import com.enterprise.ai.agent.capability.ToolAssetExecutor;
import com.enterprise.ai.runtime.contract.AiTool;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class BeanToolAssetExecutor implements ToolAssetExecutor {

    private final Map<String, AiTool> toolsByName;

    public BeanToolAssetExecutor(List<AiTool> tools) {
        this.toolsByName = tools == null ? Map.of() : tools.stream()
                .collect(Collectors.toMap(AiTool::name, Function.identity(), (a, b) -> a));
    }

    @Override
    public String executorType() {
        return "BEAN";
    }

    @Override
    public ToolRuntimeResult execute(ToolAssetEntity tool, ToolRuntimeRequest request) {
        String ref = tool.getExecutorRef();
        AiTool bean = toolsByName.get(ref);
        if (bean == null) {
            return ToolRuntimeResult.failure(tool.getQualifiedName(), "AiTool bean not found: " + ref);
        }
        return ToolRuntimeResult.success(tool.getQualifiedName(), bean.execute(request.args()));
    }
}
