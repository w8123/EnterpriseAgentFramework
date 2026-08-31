package com.enterprise.ai.capability.internal;

import com.enterprise.ai.common.capability.CapabilityInvocationFailureCategory;
import com.enterprise.ai.common.capability.CapabilityInvocationRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** Immutable ordered registry; exactly one invoker must own a resolved asset. */
@Component
public class CapabilityInvokerRegistry {

    private final List<CapabilityInvoker> invokers;

    public CapabilityInvokerRegistry(List<CapabilityInvoker> invokers) {
        this.invokers = invokers == null ? List.of() : List.copyOf(invokers);
    }

    public Map<String, Object> invoke(CapabilityInvocationAsset asset,
                                      CapabilityInvocationRequest request) {
        List<CapabilityInvoker> matches = invokers.stream()
                .filter(invoker -> invoker.supports(asset))
                .toList();
        if (matches.size() != 1) {
            throw new CapabilityInvocationPolicyException(
                    "CAPABILITY_INVOKER_CONFIGURATION_INVALID",
                    CapabilityInvocationFailureCategory.CONFIGURATION_INVALID,
                    "Capability executor must resolve to exactly one invoker: " + asset.executorType());
        }
        return matches.get(0).invoke(asset, request);
    }
}
