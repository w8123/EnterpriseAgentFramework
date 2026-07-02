package com.enterprise.ai.capability.internal;

import java.util.Map;

public record CapabilityHttpToolInvocation(String method,
                                           String url,
                                           Map<String, Object> body,
                                           Map<String, Object> metadata) {
}
