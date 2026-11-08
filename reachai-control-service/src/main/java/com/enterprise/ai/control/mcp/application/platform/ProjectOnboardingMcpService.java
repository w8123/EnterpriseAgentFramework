package com.enterprise.ai.control.mcp.application.platform;

import com.enterprise.ai.control.identity.PlatformAiConnectionService.ConnectionIdentity;
import com.enterprise.ai.control.mcp.application.port.McpPlatformProjectOnboardingPort;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ProjectOnboardingMcpService {
    private final McpPlatformProjectOnboardingPort onboarding;

    public ResponseEntity<?> start(ConnectionIdentity actor, String projectCode, String sdkLanguage, String executorProvider) {
        return PlatformAiDirectWrites.once(() -> onboarding.start(actor, projectCode, sdkLanguage, executorProvider),
                "项目接入任务状态未确认，请先用 reachai_ai_coding_tasks 查看，不要直接重试。");
    }
}
