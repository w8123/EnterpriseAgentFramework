package com.enterprise.ai.agent.capability;

import com.enterprise.ai.agent.runtime.ToolRuntimeRequest;
import com.enterprise.ai.agent.runtime.ToolRuntimeResult;

public interface ToolAssetExecutor {

    String executorType();

    ToolRuntimeResult execute(ToolAssetEntity tool, ToolRuntimeRequest request);
}
