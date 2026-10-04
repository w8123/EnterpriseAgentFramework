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
        if ("LEGACY_SCAN_TOOL_RETIRED".equals(availability)) {
            throw rejected("CAPABILITY_LEGACY_SCAN_TOOL_RETIRED",
                    "扫描人工调用投影已退场；请在所属项目 API 目录接纳并配置连接，在 Workflow 的“选择 API”重新选择后显式发布。旧引用不会自动迁移。");
        }
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

    /**
     * Console trial calls are intentionally narrower than generic Runtime Tool calls.
     * A current source observation alone is insufficient: all three owner-side hashes
     * (catalog/current, source and accepted) must still name the same contract.
     */
    public void validateConsoleAcceptedBusinessMethod(ToolDefinitionEntity tool,
                                                      Map<String, Object> constraints) {
        if (tool == null || !"BUSINESS_METHOD".equals(tool.getAssetType())) {
            throw rejected("CAPABILITY_CONSOLE_BUSINESS_METHOD_REQUIRED",
                    "控制台试调用仅支持已接受的业务方法");
        }
        Object expectedProjectId = constraints == null ? null : constraints.get("expectedProjectId");
        if (expectedProjectId == null || tool.getProjectId() == null
                || !String.valueOf(tool.getProjectId()).equals(String.valueOf(expectedProjectId).trim())) {
            throw rejected("CAPABILITY_CONSOLE_PROJECT_MISMATCH", "业务方法不属于当前项目");
        }
        String sourceKey = tool.getSourceQualifiedName();
        if (sourceKey == null || sourceKey.isBlank() || !sourceKey.equals(tool.getQualifiedName())) {
            throw rejected("CAPABILITY_CONSOLE_SOURCE_UNAVAILABLE", "业务方法缺少可验证的来源绑定");
        }
        CapabilitySourceStateEntity state = lifecycle.sourceState(sourceKey);
        String currentHash = policy.contractHash(tool);
        if (state == null
                || !currentHash.equals(state.getSourceContractHash())
                || !currentHash.equals(state.getAcceptedContractHash())
                || !"READY".equals(state.getAvailability())) {
            throw rejected("CAPABILITY_CONSOLE_CONTRACT_DRIFT",
                    "业务方法来源或已接受契约已变化，请重新确认后再试调用");
        }
    }

    private String availability(ToolDefinitionEntity tool, String currentHash) {
        String sourceKey = tool.getSourceQualifiedName();
        if (sourceKey == null || sourceKey.isBlank()) {
            // Registry auto projections also use source=scanner, but always bind a SDK identity.
            // Only persisted scanner provenance without that binding/location is the retired manual writer.
            // Never infer ownership from a name, URL or HTTP verb, and never mutate stored references.
            if ("scanner".equalsIgnoreCase(tool.getSource())
                    && !CapabilitySourceOwnership.isSdkLocation(tool.getSourceLocation())) {
                return "LEGACY_SCAN_TOOL_RETIRED";
            }
            return CapabilitySourceOwnership.isSdkLocation(tool.getSourceLocation()) ? "SOURCE_UNKNOWN" : "READY";
        }
        if (!sourceKey.equals(tool.getQualifiedName())) return "CONTRACT_DRIFT";
        CapabilitySourceStateEntity state = lifecycle.sourceState(sourceKey);
        if (state == null) return "SOURCE_UNKNOWN";
        if (state.getSourceContractHash() == null) return "SOURCE_MISSING";
        if (!state.getSourceContractHash().equals(currentHash)) return "CONTRACT_DRIFT";
        return "READY";
    }

    private CapabilityInvocationPolicyException rejected(String code, String message) {
        return new CapabilityInvocationPolicyException(code,
                CapabilityInvocationFailureCategory.CONFIGURATION_INVALID, message);
    }
}
