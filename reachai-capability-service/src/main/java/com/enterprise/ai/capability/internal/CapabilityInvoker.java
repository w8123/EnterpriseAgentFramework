package com.enterprise.ai.capability.internal;

import com.enterprise.ai.common.capability.CapabilityInvocationRequest;

import java.util.Map;

/** One transport or built-in execution strategy behind the Capability invocation boundary. */
public interface CapabilityInvoker {

    boolean supports(CapabilityInvocationAsset asset);

    Map<String, Object> invoke(CapabilityInvocationAsset asset,
                               CapabilityInvocationRequest request);
}
