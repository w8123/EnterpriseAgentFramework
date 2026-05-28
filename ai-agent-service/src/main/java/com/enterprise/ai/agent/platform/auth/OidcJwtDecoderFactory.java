package com.enterprise.ai.agent.platform.auth;

import org.springframework.security.oauth2.jwt.JwtDecoder;

@FunctionalInterface
public interface OidcJwtDecoderFactory {

    JwtDecoder create(PlatformAuthProperties.Oidc properties);
}
