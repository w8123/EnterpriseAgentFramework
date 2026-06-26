package com.enterprise.ai.agent.platform.control.auth;

import lombok.Builder;
import lombok.Value;

import java.util.Map;

@Value
@Builder(toBuilder = true)
public class PlatformLoginRequest {

    String username;

    String password;

    String providerCode;

    String providerType;

    String idToken;

    String samlResponse;

    String ip;

    String userAgent;

    @Builder.Default
    Map<String, String> headers = Map.of();

    @Builder.Default
    Map<String, Object> providerConfig = Map.of();
}
