package com.enterprise.ai.agent.platform.auth;

import java.util.Set;

public record PlatformUserProfile(
        String sourceProvider,
        String externalSubject,
        String username,
        String displayName,
        String email,
        String mobile,
        Set<String> roleCodes) {
    public PlatformUserProfile {
        roleCodes = roleCodes == null ? Set.of() : roleCodes;
    }
}
