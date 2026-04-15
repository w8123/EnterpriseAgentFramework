package com.enterprise.ai.agent.tools.definition;

import java.util.List;

public record ToolDefinitionUpsertRequest(
        String name,
        String description,
        List<ToolDefinitionParameter> parameters,
        String source,
        String sourceLocation,
        String httpMethod,
        String baseUrl,
        String contextPath,
        String endpointPath,
        String requestBodyType,
        String responseType,
        boolean enabled,
        boolean agentVisible,
        boolean lightweightEnabled
) {
}
