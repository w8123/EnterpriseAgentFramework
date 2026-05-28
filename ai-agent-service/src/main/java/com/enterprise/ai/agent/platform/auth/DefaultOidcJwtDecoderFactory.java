package com.enterprise.ai.agent.platform.auth;

import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class DefaultOidcJwtDecoderFactory implements OidcJwtDecoderFactory {

    @Override
    public JwtDecoder create(PlatformAuthProperties.Oidc properties) {
        if (StringUtils.hasText(properties.getJwkSetUri())) {
            return NimbusJwtDecoder.withJwkSetUri(properties.getJwkSetUri()).build();
        }
        if (StringUtils.hasText(properties.getIssuerUri())) {
            return JwtDecoders.fromIssuerLocation(properties.getIssuerUri());
        }
        throw new IllegalArgumentException("OIDC issuerUri or jwkSetUri is required");
    }
}
