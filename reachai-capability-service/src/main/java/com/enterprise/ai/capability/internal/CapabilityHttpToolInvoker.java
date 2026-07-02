package com.enterprise.ai.capability.internal;

import java.util.Map;

public interface CapabilityHttpToolInvoker {

    Map<String, Object> invoke(CapabilityHttpToolInvocation invocation);
}
