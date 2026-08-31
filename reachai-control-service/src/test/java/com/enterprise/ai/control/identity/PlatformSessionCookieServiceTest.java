package com.enterprise.ai.control.identity;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlatformSessionCookieServiceTest {

    @Test
    void issuesAHostOnlyApiScopedHttpOnlyCookieForLocalDevelopment() {
        PlatformSessionCookieService service = service(PlatformAuthProperties.SecureMode.AUTO);

        String header = service.issueCookie(new MockHttpServletRequest(), "opaque-token");

        assertTrue(header.startsWith(PlatformSessionCookieService.SESSION_COOKIE_NAME + "=opaque-token"));
        assertTrue(header.contains("Path=/api"));
        assertTrue(header.contains("HttpOnly"));
        assertTrue(header.contains("SameSite=Strict"));
        assertTrue(header.contains("Max-Age=86400"));
        assertFalse(header.contains("Domain="));
        assertFalse(header.contains("Secure"));
    }

    @Test
    void productionModeAlwaysMarksTheCookieSecure() {
        PlatformSessionCookieService service = service(PlatformAuthProperties.SecureMode.ALWAYS);

        String header = service.issueCookie(new MockHttpServletRequest(), "opaque-token");

        assertTrue(header.contains("Secure"));
    }

    @Test
    void autoModeRecognizesTlsTerminationFromTheForwardedProtocol() {
        PlatformSessionCookieService service = service(PlatformAuthProperties.SecureMode.AUTO);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-Proto", "https");

        assertTrue(service.issueCookie(request, "opaque-token").contains("Secure"));
        assertTrue(service.expireCookie(request).contains("Max-Age=0"));
    }

    private PlatformSessionCookieService service(PlatformAuthProperties.SecureMode secureMode) {
        PlatformAuthProperties properties = new PlatformAuthProperties();
        properties.getSessionCookie().setSecure(secureMode);
        return new PlatformSessionCookieService(properties);
    }
}
