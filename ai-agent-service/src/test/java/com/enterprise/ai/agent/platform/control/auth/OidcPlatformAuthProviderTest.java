package com.enterprise.ai.agent.platform.control.auth;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OidcPlatformAuthProviderTest {

    @Test
    void idTokenClaimsBecomeAPlatformUserProfile() {
        PlatformAuthProperties properties = enabledOidcProperties();
        Jwt jwt = Jwt.withTokenValue("id-token")
                .header("alg", "RS256")
                .issuer("https://iam.example.com")
                .subject("iam-001")
                .audience(List.of("reachai"))
                .issuedAt(Instant.now().minusSeconds(30))
                .expiresAt(Instant.now().plusSeconds(300))
                .claim("preferred_username", "zhangsan")
                .claim("name", "Zhang San")
                .claim("email", "zhangsan@example.com")
                .claim("roles", List.of("AGENT_DESIGNER", "AUDITOR"))
                .build();

        OidcPlatformAuthProvider provider = new OidcPlatformAuthProvider(properties, oidc -> token -> jwt);
        PlatformUserProfile profile = provider.authenticate(PlatformLoginRequest.builder()
                .idToken("id-token")
                .build());

        assertEquals("OIDC", profile.sourceProvider());
        assertEquals("iam-001", profile.externalSubject());
        assertEquals("zhangsan", profile.username());
        assertEquals("Zhang San", profile.displayName());
        assertEquals("zhangsan@example.com", profile.email());
        assertEquals(java.util.Set.of("AGENT_DESIGNER", "AUDITOR"), profile.roleCodes());
    }

    @Test
    void idTokenWithUnexpectedAudienceIsRejected() {
        PlatformAuthProperties properties = enabledOidcProperties();
        Jwt jwt = Jwt.withTokenValue("id-token")
                .header("alg", "RS256")
                .issuer("https://iam.example.com")
                .subject("iam-001")
                .audience(List.of("other-client"))
                .expiresAt(Instant.now().plusSeconds(300))
                .build();

        OidcPlatformAuthProvider provider = new OidcPlatformAuthProvider(properties, oidc -> token -> jwt);

        assertThrows(IllegalArgumentException.class, () -> provider.authenticate(PlatformLoginRequest.builder()
                .idToken("id-token")
                .build()));
    }

    @Test
    void runtimeProviderConfigCanEnableAndConfigureOidc() {
        PlatformAuthProperties properties = new PlatformAuthProperties();
        Jwt jwt = Jwt.withTokenValue("id-token")
                .header("alg", "RS256")
                .issuer("https://runtime-iam.example.com")
                .subject("iam-002")
                .audience(List.of("runtime-client"))
                .expiresAt(Instant.now().plusSeconds(300))
                .claim("upn", "lisi")
                .claim("runtimeRoles", List.of("OPERATOR"))
                .build();

        OidcPlatformAuthProvider provider = new OidcPlatformAuthProvider(properties, oidc -> {
            assertEquals("https://runtime-iam.example.com", oidc.getIssuerUri());
            assertEquals("runtime-client", oidc.getClientId());
            assertEquals("upn", oidc.getUsernameClaim());
            assertEquals("runtimeRoles", oidc.getRolesClaim());
            return token -> jwt;
        });
        PlatformUserProfile profile = provider.authenticate(PlatformLoginRequest.builder()
                .idToken("id-token")
                .providerConfig(Map.of(
                        "enabled", true,
                        "issuerUri", "https://runtime-iam.example.com",
                        "clientId", "runtime-client",
                        "usernameClaim", "upn",
                        "rolesClaim", "runtimeRoles"))
                .build());

        assertEquals("lisi", profile.username());
        assertEquals(java.util.Set.of("OPERATOR"), profile.roleCodes());
    }

    private PlatformAuthProperties enabledOidcProperties() {
        PlatformAuthProperties properties = new PlatformAuthProperties();
        properties.setProvider("OIDC");
        properties.getOidc().setEnabled(true);
        properties.getOidc().setIssuerUri("https://iam.example.com");
        properties.getOidc().setClientId("reachai");
        return properties;
    }
}
