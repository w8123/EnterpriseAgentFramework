package com.enterprise.ai.agent.runtime.host;


import com.enterprise.ai.agent.runtime.*;
import org.springframework.stereotype.Component;

@Component
public class CursorCodeAgentRuntimeAdapter extends AbstractUnavailableRuntimeAdapter {

    public CursorCodeAgentRuntimeAdapter() {
        super(AgentRuntimeCapability.builder()
                .runtimeType(CURSOR_CODE_AGENT_RUNTIME_TYPE)
                .displayName("Cursor Code Agent")
                .description("面向代码理解、代码生成、测试生成与 PR 产物的工程型 Runtime。")
                .available(false)
                .unavailableReason("Cursor SDK 适配器尚未接入 workspace、artifact 与审批边界")
                .supportedModelType("LLM")
                .supportsStreaming(true)
                .supportsTools(false)
                .supportsHandoff(false)
                .supportsGraph(false)
                .supportsHumanInterrupt(true)
                .supportsArtifacts(true)
                .supportsCodeWorkspace(true)
                .supportsCloudExecution(true)
                .securityLevel("CODE_WORKSPACE_APPROVAL")
                .build());
    }
}
