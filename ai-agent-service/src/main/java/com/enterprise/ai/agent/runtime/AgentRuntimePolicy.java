package com.enterprise.ai.agent.runtime;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class AgentRuntimePolicy {

    private final AgentRuntimePolicyProperties properties;

    public AgentRuntimePolicy(AgentRuntimePolicyProperties properties) {
        this.properties = properties;
    }

    public String defaultRuntimeType() {
        return normalize(properties.getDefaultRuntimeType());
    }

    public AgentRuntimeCapability apply(AgentRuntimeCapability base) {
        if (base == null) {
            return null;
        }
        String disabledReason = disabledReason(base, null);
        boolean available = base.isAvailable() && disabledReason == null;
        return copy(base, available, disabledReason == null ? base.getUnavailableReason() : disabledReason);
    }

    public void validate(AgentRuntimeCapability capability, AgentRuntimeRequest request) {
        String projectCode = null;
        if (request != null) {
            if (request.getGraphRuntimeContext() != null) {
                projectCode = request.getGraphRuntimeContext().getProjectCode();
            } else if (request.getAgentRuntimeProfile() != null) {
                projectCode = request.getAgentRuntimeProfile().getProjectCode();
            }
        }
        String disabledReason = disabledReason(capability, projectCode);
        if (disabledReason != null) {
            throw new IllegalStateException("Agent Runtime 不符合平台策略: "
                    + capability.getRuntimeType() + "，原因：" + disabledReason);
        }
    }

    private String disabledReason(AgentRuntimeCapability capability, String projectCode) {
        String runtimeType = normalize(capability.getRuntimeType());
        if (!enabledRuntimeSet().contains(runtimeType)) {
            return "运行时未在 ai.agent-runtime.enabled-runtimes 中启用";
        }
        if (capability.isSupportsCloudExecution()) {
            if (!properties.isAllowCloudExecution()) {
                return "云端执行未开启";
            }
            if (projectCode != null && !projectCode.isBlank()
                    && !normalizedSet(properties.getCloudAllowedProjectCodes()).contains(normalize(projectCode))) {
                return "当前项目未加入云端执行白名单";
            }
        }
        if (capability.isSupportsCodeWorkspace()) {
            if (!properties.isAllowCodeWorkspace()) {
                return "代码工作区执行未开启";
            }
            if (projectCode != null && !projectCode.isBlank()
                    && !normalizedSet(properties.getCodeWorkspaceAllowedProjectCodes()).contains(normalize(projectCode))) {
                return "当前项目未加入代码工作区白名单";
            }
        }
        return null;
    }

    private Set<String> enabledRuntimeSet() {
        Set<String> enabled = normalizedSet(properties.getEnabledRuntimes());
        if (enabled.isEmpty()) {
            return Set.of(defaultRuntimeType());
        }
        return enabled;
    }

    private static Set<String> normalizedSet(Iterable<String> values) {
        if (values == null) {
            return Set.of();
        }
        return java.util.stream.StreamSupport.stream(values.spliterator(), false)
                .filter(value -> value != null && !value.isBlank())
                .map(AgentRuntimePolicy::normalize)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return AgentRuntimeAdapter.DEFAULT_RUNTIME_TYPE;
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private static AgentRuntimeCapability copy(AgentRuntimeCapability base,
                                               boolean available,
                                               String unavailableReason) {
        return AgentRuntimeCapability.builder()
                .runtimeType(base.getRuntimeType())
                .displayName(base.getDisplayName())
                .description(base.getDescription())
                .available(available)
                .unavailableReason(available ? null : unavailableReason)
                .supportedModelTypes(base.getSupportedModelTypes())
                .supportsStreaming(base.isSupportsStreaming())
                .supportsTools(base.isSupportsTools())
                .supportsHandoff(base.isSupportsHandoff())
                .supportsGraph(base.isSupportsGraph())
                .supportsHumanInterrupt(base.isSupportsHumanInterrupt())
                .supportsArtifacts(base.isSupportsArtifacts())
                .supportsCodeWorkspace(base.isSupportsCodeWorkspace())
                .supportsCloudExecution(base.isSupportsCloudExecution())
                .securityLevel(base.getSecurityLevel())
                .build();
    }
}
