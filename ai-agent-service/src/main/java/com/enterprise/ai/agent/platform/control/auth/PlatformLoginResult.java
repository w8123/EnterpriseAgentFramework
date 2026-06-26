package com.enterprise.ai.agent.platform.control.auth;

import java.time.LocalDateTime;

public record PlatformLoginResult(
        String accessToken,
        long expiresIn,
        LocalDateTime expiresAt,
        PlatformPrincipal principal
) {
}
