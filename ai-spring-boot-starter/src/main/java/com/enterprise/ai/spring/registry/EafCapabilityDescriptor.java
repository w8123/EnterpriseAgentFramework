package com.enterprise.ai.spring.registry;

import java.util.List;
import java.util.Map;

public record EafCapabilityDescriptor(
        String name,
        String title,
        String description,
        String httpMethod,
        String baseUrl,
        String contextPath,
        String endpointPath,
        String requestBodyType,
        String responseType,
        String sideEffect,
        Boolean enabled,
        Boolean agentVisible,
        Boolean lightweightEnabled,
        String visibility,
        List<EafCapabilityParameter> parameters,
        Map<String, Object> metadata
) {
}
