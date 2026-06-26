package com.enterprise.ai.agent.runtime.host;


import com.enterprise.ai.agent.runtime.*;
import org.springframework.stereotype.Component;

@Component
public class OpenAIAgentsRuntimeAdapter extends AbstractUnavailableRuntimeAdapter {

    public OpenAIAgentsRuntimeAdapter() {
        super(AgentRuntimeCapability.builder()
                .runtimeType(OPENAI_AGENTS_RUNTIME_TYPE)
                .displayName("OpenAI Agents")
                .description("面向 handoff、guardrails 与 tracing 的 OpenAI Agents SDK Runtime。")
                .available(false)
                .unavailableReason("OpenAI Agents SDK 适配器尚未接入依赖与模型凭证映射")
                .supportedModelType("LLM")
                .supportsStreaming(true)
                .supportsTools(true)
                .supportsHandoff(true)
                .supportsGraph(false)
                .supportsHumanInterrupt(true)
                .supportsArtifacts(true)
                .supportsCodeWorkspace(false)
                .supportsCloudExecution(true)
                .securityLevel("CLOUD_RESTRICTED")
                .build());
    }
}
