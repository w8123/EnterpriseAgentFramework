package com.enterprise.ai.control.aicoding.api;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AiCodingRequestSupportTest {

    @Test
    void usesDirectListenerAuthorityWithoutDefaultPortNoise() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setScheme("http");
        request.setServerName("localhost");
        request.setServerPort(18603);
        request.setContextPath("");

        assertEquals(
                "http://localhost:18603",
                AiCodingRequestSupport.publicBaseUrl(request));
    }

    @Test
    void neverLeaksInternalListenerPortBehindForwardedHttpsHost() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setScheme("http");
        request.setServerName("reachai-control");
        request.setServerPort(18603);
        request.addHeader("X-Forwarded-Proto", "https");
        request.addHeader("X-Forwarded-Host", "reachai.example.com");

        assertEquals(
                "https://reachai.example.com",
                AiCodingRequestSupport.publicBaseUrl(request));
    }

    @Test
    void honorsExplicitPublicForwardedPort() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setScheme("http");
        request.setServerName("reachai-control");
        request.setServerPort(18603);
        request.addHeader("X-Forwarded-Proto", "https");
        request.addHeader("X-Forwarded-Host", "reachai.example.com");
        request.addHeader("X-Forwarded-Port", "8443");

        assertEquals(
                "https://reachai.example.com:8443",
                AiCodingRequestSupport.publicBaseUrl(request));
    }

    @Test
    void formatsValidatedIpv6Authorities() {
        MockHttpServletRequest forwarded = new MockHttpServletRequest();
        forwarded.setScheme("http");
        forwarded.setServerName("reachai-control");
        forwarded.setServerPort(18603);
        forwarded.addHeader("X-Forwarded-Proto", "https");
        forwarded.addHeader("X-Forwarded-Host", "[2001:db8::10]:8443");

        assertEquals(
                "https://[2001:db8::10]:8443",
                AiCodingRequestSupport.publicBaseUrl(forwarded));

        MockHttpServletRequest direct = new MockHttpServletRequest();
        direct.setScheme("http");
        direct.setServerName("2001:db8::10");
        direct.setServerPort(18603);

        assertEquals(
                "http://[2001:db8::10]:18603",
                AiCodingRequestSupport.publicBaseUrl(direct));
    }

    @Test
    void rejectsUntrustedForwardedAuthoritiesWithPaths() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setScheme("http");
        request.setServerName("localhost");
        request.setServerPort(18603);
        request.addHeader("X-Forwarded-Host", "evil.example/path");

        assertThrows(
                IllegalArgumentException.class,
                () -> AiCodingRequestSupport.publicBaseUrl(request));
    }

    @Test
    void rejectsMalformedBracketedAndEmptyAuthorities() {
        String[] invalidAuthorities = {
                ":8080",
                "[]:8080",
                "[::1",
                "[::1]evil",
                "[::1]:",
                "[not-ipv6]:8443",
                "reachai.example.com[::1]"
        };
        for (String authority : invalidAuthorities) {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setScheme("http");
            request.setServerName("localhost");
            request.setServerPort(18603);
            request.addHeader("X-Forwarded-Host", authority);

            assertThrows(
                    IllegalArgumentException.class,
                    () -> AiCodingRequestSupport.publicBaseUrl(request),
                    authority);
        }
    }
}
