package com.enterprise.ai.agent.platform.control.auth;

public interface PlatformAuthProvider {

    String providerType();

    PlatformUserProfile authenticate(PlatformLoginRequest request);
}
