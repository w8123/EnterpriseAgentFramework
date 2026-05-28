package com.enterprise.ai.agent.platform.auth;

public interface PlatformAuthProvider {

    String providerType();

    PlatformUserProfile authenticate(PlatformLoginRequest request);
}
