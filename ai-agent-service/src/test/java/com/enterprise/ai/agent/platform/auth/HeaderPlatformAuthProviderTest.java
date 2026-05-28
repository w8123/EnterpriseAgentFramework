package com.enterprise.ai.agent.platform.auth;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HeaderPlatformAuthProviderTest {

    @Test
    void trustedGatewayHeadersBecomeAPlatformUserProfile() {
        PlatformAuthProperties properties = new PlatformAuthProperties();
        properties.setProvider("HEADER");
        properties.getHeader().setEnabled(true);

        HeaderPlatformAuthProvider provider = new HeaderPlatformAuthProvider(properties);
        PlatformUserProfile profile = provider.authenticate(PlatformLoginRequest.builder()
                .headers(Map.of(
                        "X-EAF-User", "zhangsan",
                        "X-EAF-Subject", "iam-001",
                        "X-EAF-Display-Name", "Zhang San",
                        "X-EAF-Roles", "AGENT_DESIGNER, AUDITOR"))
                .build());

        assertEquals("HEADER", profile.sourceProvider());
        assertEquals("iam-001", profile.externalSubject());
        assertEquals("zhangsan", profile.username());
        assertEquals("Zhang San", profile.displayName());
        assertEquals(java.util.Set.of("AGENT_DESIGNER", "AUDITOR"), profile.roleCodes());
    }

    @Test
    void missingGatewaySubjectIsRejected() {
        PlatformAuthProperties properties = new PlatformAuthProperties();
        properties.setProvider("HEADER");
        properties.getHeader().setEnabled(true);

        HeaderPlatformAuthProvider provider = new HeaderPlatformAuthProvider(properties);

        assertThrows(IllegalArgumentException.class, () -> provider.authenticate(PlatformLoginRequest.builder()
                .headers(Map.of("X-EAF-User", "zhangsan"))
                .build()));
    }

    @Test
    void runtimeProviderConfigCanEnableAndMapTrustedHeaders() {
        PlatformAuthProperties properties = new PlatformAuthProperties();
        HeaderPlatformAuthProvider provider = new HeaderPlatformAuthProvider(properties);

        PlatformUserProfile profile = provider.authenticate(PlatformLoginRequest.builder()
                .providerConfig(Map.of(
                        "enabled", true,
                        "usernameHeader", "X-SSO-Login",
                        "subjectHeader", "X-SSO-Subject",
                        "displayNameHeader", "X-SSO-Name",
                        "rolesHeader", "X-SSO-Roles",
                        "rolesDelimiter", "|"))
                .headers(Map.of(
                        "X-SSO-Login", "lisi",
                        "X-SSO-Subject", "iam-002",
                        "X-SSO-Name", "Li Si",
                        "X-SSO-Roles", "PROJECT_OWNER|AUDITOR"))
                .build());

        assertEquals("iam-002", profile.externalSubject());
        assertEquals("lisi", profile.username());
        assertEquals("Li Si", profile.displayName());
        assertEquals(java.util.Set.of("PROJECT_OWNER", "AUDITOR"), profile.roleCodes());
    }
}
