package com.enterprise.ai.capability.internal;

import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionEntity;
import com.enterprise.ai.capability.catalog.CapabilitySourceOwnership;
import com.enterprise.ai.capability.registry.CapabilityChangeLifecycle;
import com.enterprise.ai.capability.registry.CapabilityChangePolicy;
import com.enterprise.ai.capability.registry.CapabilitySourceStateEntity;
import com.enterprise.ai.common.capability.CapabilityInvocationFailureCategory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Applied immediately before outbound HTTP; ignores catalog decision labels when checking source truth. */
@Component
@RequiredArgsConstructor
public class CapabilitySourceContractGuard {
    private final CapabilityChangeLifecycle lifecycle;
    private final CapabilityChangePolicy policy;

    public void validate(ToolDefinitionEntity tool, Map<String, Object> constraints) {
        String currentHash = policy.contractHash(tool);
        String availability = availability(tool, currentHash);
        if (!"READY".equals(availability)) {
            throw new CapabilityInvocationPolicyException("CAPABILITY_" + availability,
                    CapabilityInvocationFailureCategory.CONFIGURATION_INVALID,
                    "能力来源与当前目录尚未确认一致，请在能力目录处理来源变化: " + tool.getQualifiedName());
        }
        Object expected = constraints == null ? null : constraints.get("expectedContractHash");
        if (expected != null && !currentHash.equals(String.valueOf(expected))) {
            throw new CapabilityInvocationPolicyException("CAPABILITY_PUBLISHED_CONTRACT_CHANGED",
                    CapabilityInvocationFailureCategory.CONFIGURATION_INVALID,
                    "能力契约已变化，请校验并重新发布引用它的 Workflow: " + tool.getQualifiedName());
        }
    }

    public String availability(ToolDefinitionEntity tool) { return availability(tool, policy.contractHash(tool)); }

    private String availability(ToolDefinitionEntity tool, String currentHash) {
        String sourceKey = tool.getSourceQualifiedName();
        if (sourceKey == null || sourceKey.isBlank()) {
            return CapabilitySourceOwnership.isSdkLocation(tool.getSourceLocation()) ? "SOURCE_UNKNOWN" : "READY";
        }
        if (!sourceKey.equals(tool.getQualifiedName())) return "CONTRACT_DRIFT";
        CapabilitySourceStateEntity state = lifecycle.sourceState(sourceKey);
        if (state == null) return "SOURCE_UNKNOWN";
        if (state.getSourceContractHash() == null) return "SOURCE_MISSING";
        if (!state.getSourceContractHash().equals(currentHash)) return "CONTRACT_DRIFT";
        return "READY";
    }
}
