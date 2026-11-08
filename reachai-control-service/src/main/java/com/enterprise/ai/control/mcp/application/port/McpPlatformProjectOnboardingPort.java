package com.enterprise.ai.control.mcp.application.port;

import com.enterprise.ai.control.identity.PlatformAiConnectionService.ConnectionIdentity;
import org.springframework.http.ResponseEntity;

public interface McpPlatformProjectOnboardingPort {
    ResponseEntity<?> start(ConnectionIdentity actor, String projectCode, String sdkLanguage, String executorProvider);
}
