package com.enterprise.ai.agent.runtime.host;


import com.enterprise.ai.agent.runtime.*;
public abstract class AbstractUnavailableRuntimeAdapter implements AgentRuntimeAdapter {

    private final AgentRuntimeCapability capability;

    protected AbstractUnavailableRuntimeAdapter(AgentRuntimeCapability capability) {
        this.capability = capability;
    }

    @Override
    public String runtimeType() {
        return capability.getRuntimeType();
    }

    @Override
    public AgentRuntimeCapability capability() {
        return capability;
    }

    @Override
    public boolean supports(AgentRuntimeRequest request) {
        return false;
    }

    @Override
    public AgentRuntimeResult execute(AgentRuntimeRequest request) {
        return AgentRuntimeResult.builder()
                .success(false)
                .runtimeType(runtimeType())
                .traceId(request == null ? null : request.getTraceId())
                .answer("当前运行时不可用：" + capability.getUnavailableReason())
                .errorCode("RUNTIME_UNAVAILABLE")
                .errorMessage(capability.getUnavailableReason())
                .build();
    }
}
