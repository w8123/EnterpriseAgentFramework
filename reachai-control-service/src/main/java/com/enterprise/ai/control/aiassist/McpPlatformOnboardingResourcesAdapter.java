package com.enterprise.ai.control.aiassist;

import com.enterprise.ai.control.mcp.application.port.McpPlatformOnboardingResourcesPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class McpPlatformOnboardingResourcesAdapter implements McpPlatformOnboardingResourcesPort {
    private final ControlAiAssistArtifactController artifacts;

    @Override
    public Object manifest(String baseUrl) {
        return artifacts.manifestForBaseUrl(baseUrl);
    }
}
