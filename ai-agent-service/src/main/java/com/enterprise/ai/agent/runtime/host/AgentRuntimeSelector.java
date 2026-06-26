package com.enterprise.ai.agent.runtime.host;


import com.enterprise.ai.agent.runtime.*;
import com.enterprise.ai.agent.runtime.AgentRuntimeProfile;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Comparator;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Component
public class AgentRuntimeSelector {

    private final Map<String, AgentRuntimeAdapter> adapters;
    private final AgentRuntimePolicy policy;
    private final AgentRuntimeModelValidator modelValidator;

    public AgentRuntimeSelector(List<AgentRuntimeAdapter> adapters,
                                AgentRuntimePolicy policy,
                                AgentRuntimeModelValidator modelValidator) {
        this.policy = policy;
        this.modelValidator = modelValidator;
        this.adapters = adapters.stream()
                .collect(Collectors.toUnmodifiableMap(
                        adapter -> normalize(adapter.runtimeType()),
                        Function.identity(),
                        (a, b) -> a));
        log.info("[AgentRuntime] 已加载运行时适配器: {}", this.adapters.keySet());
    }

    public List<AgentRuntimeCapability> capabilities() {
        return adapters.values().stream()
                .map(AgentRuntimeAdapter::capability)
                .map(policy::apply)
                .sorted(Comparator.comparing(AgentRuntimeCapability::isAvailable).reversed()
                        .thenComparing(AgentRuntimeCapability::getRuntimeType))
                .toList();
    }

    public AgentRuntimeAdapter select(AgentRuntimeRequest request) {
        Selection selection = validateSelection(request);
        return selection.adapter();
    }

    public AgentRuntimeValidationResult validate(AgentRuntimeRequest request) {
        try {
            Selection selection = validateSelection(request);
            var model = selection.modelInstance();
            return AgentRuntimeValidationResult.builder()
                    .valid(true)
                    .runtimeType(selection.adapter().runtimeType())
                    .modelInstanceId(model == null ? null : model.getId())
                    .modelType(model == null ? null : model.getModelType())
                    .provider(model == null ? null : model.getProvider())
                    .message("ok")
                    .build();
        } catch (Exception ex) {
            AgentRuntimeProfile profile = request == null ? null : request.getAgentRuntimeProfile();
            GraphRuntimeContext runtimeContext = request == null ? null : request.getGraphRuntimeContext();
            return AgentRuntimeValidationResult.builder()
                    .valid(false)
                    .runtimeType(resolveRuntimeType(request))
                    .modelInstanceId(runtimeContext == null
                            ? profile == null ? null : profile.getModelInstanceId()
                            : runtimeContext.getModelInstanceId())
                    .message(ex.getMessage())
                    .errorCode(ex.getClass().getSimpleName())
                    .build();
        }
    }

    private Selection validateSelection(AgentRuntimeRequest request) {
        String runtimeType = resolveRuntimeType(request);
        AgentRuntimeAdapter adapter = adapters.get(normalize(runtimeType));
        if (adapter == null) {
            throw new IllegalStateException("未找到 Agent Runtime Adapter: " + runtimeType);
        }
        AgentRuntimeCapability capability = adapter.capability();
        policy.validate(capability, request);
        if (capability != null && !capability.isAvailable()) {
            throw new IllegalStateException("Agent Runtime 不可用: " + runtimeType + "，原因：" + capability.getUnavailableReason());
        }
        var modelInstance = modelValidator.validate(request, capability);
        if (!adapter.supports(request)) {
            throw new IllegalStateException(adapter.unsupportedReason(request));
        }
        return new Selection(adapter, modelInstance);
    }

    private record Selection(AgentRuntimeAdapter adapter,
                             com.enterprise.ai.agent.client.ModelServiceClient.ModelInstanceData modelInstance) {}

    private String resolveRuntimeType(AgentRuntimeRequest request) {
        if (request != null && request.getGraphRuntimeContext() != null
                && request.getGraphRuntimeContext().getRuntimeType() != null
                && !request.getGraphRuntimeContext().getRuntimeType().isBlank()) {
            return request.getGraphRuntimeContext().getRuntimeType();
        }
        AgentRuntimeProfile profile = request == null ? null : request.getAgentRuntimeProfile();
        if (profile != null && profile.getRuntimeType() != null && !profile.getRuntimeType().isBlank()) {
            return profile.getRuntimeType();
        }
        return policy.defaultRuntimeType();
    }

    private static String normalize(String runtimeType) {
        if (runtimeType == null || runtimeType.isBlank()) {
            return AgentRuntimeAdapter.DEFAULT_RUNTIME_TYPE;
        }
        return runtimeType.trim().toUpperCase(Locale.ROOT);
    }
}
