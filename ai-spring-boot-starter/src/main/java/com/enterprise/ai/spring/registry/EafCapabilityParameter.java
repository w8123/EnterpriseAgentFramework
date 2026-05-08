package com.enterprise.ai.spring.registry;

import java.util.List;
import java.util.Map;

public record EafCapabilityParameter(
        String name,
        String type,
        String description,
        boolean required,
        String location,
        List<EafCapabilityParameter> children,
        Map<String, Object> metadata
) {
}
