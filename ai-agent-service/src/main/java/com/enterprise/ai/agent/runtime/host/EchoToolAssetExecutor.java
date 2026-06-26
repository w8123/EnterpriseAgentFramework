package com.enterprise.ai.agent.runtime.host;


import com.enterprise.ai.agent.runtime.*;
import com.enterprise.ai.agent.capability.ToolAssetEntity;
import com.enterprise.ai.agent.capability.ToolAssetExecutor;
import org.springframework.stereotype.Component;

@Component
public class EchoToolAssetExecutor implements ToolAssetExecutor {

    @Override
    public String executorType() {
        return "ECHO";
    }

    @Override
    public ToolRuntimeResult execute(ToolAssetEntity tool, ToolRuntimeRequest request) {
        return ToolRuntimeResult.success(tool.getQualifiedName(), request.args());
    }
}
