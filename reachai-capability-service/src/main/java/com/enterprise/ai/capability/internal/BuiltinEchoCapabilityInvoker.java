package com.enterprise.ai.capability.internal;

import com.enterprise.ai.common.capability.CapabilityInvocationRequest;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** Deterministic built-in invoker used by the seeded system.echo Capability. */
@Component
public class BuiltinEchoCapabilityInvoker implements CapabilityInvoker {

    @Override
    public boolean supports(CapabilityInvocationAsset asset) {
        return asset.source() == CapabilityInvocationAsset.Source.KERNEL_ASSET
                && "ECHO".equals(asset.executorType());
    }

    @Override
    public Map<String, Object> invoke(CapabilityInvocationAsset asset,
                                      CapabilityInvocationRequest request) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("toolName", asset.name());
        result.put("toolTitle", asset.title());
        result.put("success", true);
        result.put("data", request.input());
        result.put("code", "CAPABILITY_EXECUTED");
        result.put("message", "Capability invocation completed");
        result.put("metadata", Map.of("transport", "BUILTIN"));
        return result;
    }
}
