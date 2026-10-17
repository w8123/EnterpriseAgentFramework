package com.enterprise.ai.control.identity;

import com.enterprise.ai.control.internalauth.InternalServiceAuthRequestAttributes;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PlatformConsoleAuthInterceptorTest {

    @Test
    void rejectsMissingOrInvalidPlatformLogin() throws Exception {
        PlatformBearerAuthService authService =
                mock(PlatformBearerAuthService.class);
        when(authService.resolveBearerSession(null))
                .thenReturn(Optional.empty());
        PlatformConsoleAuthInterceptor interceptor =
                new PlatformConsoleAuthInterceptor(authService, cookieService());

        MockHttpServletResponse response = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(
                new MockHttpServletRequest(
                        "GET",
                        "/api/ai-coding-console/tasks"),
                response,
                new Object()));
        assertEquals(401, response.getStatus());
        assertEquals(
                PlatformConsoleAuthInterceptor.PLATFORM_SESSION_INVALID,
                response.getHeader(PlatformConsoleAuthInterceptor.AUTH_FAILURE_HEADER));
        assertTrue(response.getContentAsString().contains("PLATFORM_SESSION_INVALID"));
        assertTrue(response.getContentAsString().contains("LOGIN_REQUIRED"));
    }

    @Test
    void attachesAuthenticatedUserAndAllowsConsoleRequest() {
        PlatformBearerAuthService authService =
                mock(PlatformBearerAuthService.class);
        PlatformUserEntity user = new PlatformUserEntity();
        user.setId(7L);
        PlatformLoginSessionEntity loginSession = new PlatformLoginSessionEntity();
        loginSession.setSessionId("pls_7");
        PlatformAuthenticatedSession session = new PlatformAuthenticatedSession(
                PlatformPrincipal.fromUser(user),
                "pls_7",
                null,
                java.util.List.of("PLATFORM_ADMIN"),
                java.util.List.of("*"),
                java.util.List.of(new PlatformPermissionGrant("*", "GLOBAL", "*")));
        when(authService.resolveBearerSession("Bearer platform-token"))
                .thenReturn(Optional.of(session));
        PlatformConsoleAuthInterceptor interceptor =
                new PlatformConsoleAuthInterceptor(authService, cookieService());
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET",
                "/api/ai-coding-console/tasks");
        request.addHeader(
                HttpHeaders.AUTHORIZATION,
                "Bearer platform-token");

        assertTrue(interceptor.preHandle(
                request,
                new MockHttpServletResponse(),
                new Object()));
        assertSame(
                session.user(),
                request.getAttribute(
                        PlatformConsoleAuthInterceptor.USER_REQUEST_ATTRIBUTE));
        assertSame(
                session,
                request.getAttribute(
                        PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE));
        assertEquals(
                "7",
                request.getAttribute(
                        InternalServiceAuthRequestAttributes.PLATFORM_SESSION_ACTOR_ID));
    }

    @Test
    void returnsRealHttp401BeforeDispatchingAProtectedController() throws Exception {
        PlatformBearerAuthService authService = mock(PlatformBearerAuthService.class);
        when(authService.resolveBearerSession(null)).thenReturn(Optional.empty());
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ProtectedProbeController())
                .addInterceptors(new PlatformConsoleAuthInterceptor(authService, cookieService()))
                .build();

        mockMvc.perform(get("/api/workflows/probe"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(
                        PlatformConsoleAuthInterceptor.AUTH_FAILURE_HEADER,
                        PlatformConsoleAuthInterceptor.PLATFORM_SESSION_INVALID));
    }

    @Test
    void allowsCorsPreflightWithoutConsumingLoginSession() {
        PlatformConsoleAuthInterceptor interceptor =
                new PlatformConsoleAuthInterceptor(
                        mock(PlatformBearerAuthService.class),
                        cookieService());
        assertTrue(interceptor.preHandle(
                new MockHttpServletRequest(
                        "OPTIONS",
                        "/api/ai-coding-console/tasks"),
                new MockHttpServletResponse(),
                new Object()));
    }

    @Test
    void authenticatesAReadRequestFromTheHttpOnlySessionCookie() {
        PlatformBearerAuthService authService = mock(PlatformBearerAuthService.class);
        PlatformAuthenticatedSession session = authenticatedSession("pls_cookie");
        when(authService.resolveSessionToken("raw-cookie-token"))
                .thenReturn(Optional.of(session));
        PlatformConsoleAuthInterceptor interceptor =
                new PlatformConsoleAuthInterceptor(authService, cookieService());
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET",
                "/api/platform/auth/me");
        request.setCookies(new Cookie(
                PlatformSessionCookieService.SESSION_COOKIE_NAME,
                "raw-cookie-token"));

        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));

        verify(authService).resolveSessionToken("raw-cookie-token");
        assertSame(session, request.getAttribute(
                PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE));
    }

    @Test
    void requiresTheSessionBoundCsrfHeaderForCookieAuthenticatedWrites() throws Exception {
        PlatformBearerAuthService authService = mock(PlatformBearerAuthService.class);
        PlatformAuthenticatedSession session = authenticatedSession("pls_cookie");
        when(authService.resolveSessionToken("raw-cookie-token"))
                .thenReturn(Optional.of(session));
        PlatformConsoleAuthInterceptor interceptor =
                new PlatformConsoleAuthInterceptor(authService, cookieService());
        MockHttpServletRequest rejected = cookieRequest("POST", "raw-cookie-token");
        MockHttpServletResponse rejectedResponse = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(rejected, rejectedResponse, new Object()));
        assertEquals(403, rejectedResponse.getStatus());
        assertTrue(rejectedResponse.getContentAsString().contains(
                PlatformConsoleAuthInterceptor.PLATFORM_CSRF_INVALID));
        assertEquals(null, rejectedResponse.getHeader(
                PlatformConsoleAuthInterceptor.AUTH_FAILURE_HEADER));

        MockHttpServletRequest accepted = cookieRequest("POST", "raw-cookie-token");
        accepted.addHeader(PlatformConsoleAuthInterceptor.CSRF_HEADER, "pls_cookie");
        assertTrue(interceptor.preHandle(accepted, new MockHttpServletResponse(), new Object()));
    }

    @Test
    void keepsBearerCompatibilityWithoutApplyingBrowserCsrfRules() {
        PlatformBearerAuthService authService = mock(PlatformBearerAuthService.class);
        when(authService.resolveBearerSession("Bearer compatibility-token"))
                .thenReturn(Optional.of(authenticatedSession("pls_bearer")));
        PlatformConsoleAuthInterceptor interceptor =
                new PlatformConsoleAuthInterceptor(authService, cookieService());
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST",
                "/api/runtime/agents/execute");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer compatibility-token");

        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
    }

    @Test
    void expiresAnInvalidBrowserSessionCookie() {
        PlatformBearerAuthService authService = mock(PlatformBearerAuthService.class);
        when(authService.resolveSessionToken("revoked-cookie-token"))
                .thenReturn(Optional.empty());
        PlatformConsoleAuthInterceptor interceptor =
                new PlatformConsoleAuthInterceptor(authService, cookieService());
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(
                cookieRequest("GET", "revoked-cookie-token"),
                response,
                new Object()));

        assertEquals(401, response.getStatus());
        String setCookie = response.getHeader(HttpHeaders.SET_COOKIE);
        assertTrue(setCookie.startsWith(PlatformSessionCookieService.SESSION_COOKIE_NAME + "="));
        assertTrue(setCookie.contains("Max-Age=0"));
        assertTrue(setCookie.contains("HttpOnly"));
    }

    @Test
    void skipsAStaleApiScopedCookieAndReachesKnowledgeWithTheLiveSession() {
        PlatformBearerAuthService authService = mock(PlatformBearerAuthService.class);
        when(authService.resolveSessionToken("stale-api-token")).thenReturn(Optional.empty());
        when(authService.resolveSessionToken("live-token"))
                .thenReturn(Optional.of(authenticatedSession("pls_live")));
        PlatformConsoleAuthInterceptor interceptor =
                new PlatformConsoleAuthInterceptor(authService, cookieService());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ai/knowledge/list");
        request.setCookies(
                new Cookie(PlatformSessionCookieService.SESSION_COOKIE_NAME, "stale-api-token"),
                new Cookie(PlatformSessionCookieService.SESSION_COOKIE_NAME, "live-token"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request, response, new Object()));

        String setCookie = response.getHeader(HttpHeaders.SET_COOKIE);
        assertTrue(setCookie.contains("Path=/api"));
        assertTrue(setCookie.contains("Max-Age=0"));
    }

    private static PlatformSessionCookieService cookieService() {
        return new PlatformSessionCookieService(new PlatformAuthProperties());
    }

    private static MockHttpServletRequest cookieRequest(String method, String token) {
        MockHttpServletRequest request = new MockHttpServletRequest(
                method,
                "/api/platform/users");
        request.setCookies(new Cookie(
                PlatformSessionCookieService.SESSION_COOKIE_NAME,
                token));
        return request;
    }

    private static PlatformAuthenticatedSession authenticatedSession(String sessionId) {
        PlatformUserEntity user = new PlatformUserEntity();
        user.setId(7L);
        return new PlatformAuthenticatedSession(
                PlatformPrincipal.fromUser(user),
                sessionId,
                null,
                java.util.List.of("PLATFORM_ADMIN"),
                java.util.List.of("*"),
                java.util.List.of(new PlatformPermissionGrant("*", "GLOBAL", "*")));
    }

    private static PlatformAuthenticatedSession sessionWith(PlatformPermissionGrant... grants) {
        PlatformUserEntity user = new PlatformUserEntity();
        user.setId(8L);
        java.util.List<PlatformPermissionGrant> grantList = java.util.List.of(grants);
        return new PlatformAuthenticatedSession(
                PlatformPrincipal.fromUser(user),
                "pls_scoped",
                null,
                java.util.List.of("CUSTOM"),
                grantList.stream().map(PlatformPermissionGrant::permissionCode).distinct().toList(),
                grantList);
    }

    private static boolean admitsBearer(PlatformAuthenticatedSession session, String method, String path,
                                        MockHttpServletResponse response) {
        PlatformBearerAuthService authService = mock(PlatformBearerAuthService.class);
        when(authService.resolveBearerSession("Bearer scoped")).thenReturn(Optional.of(session));
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer scoped");
        return new PlatformConsoleAuthInterceptor(authService, cookieService())
                .preHandle(request, response, new Object());
    }

    @Test
    void rejectsALoginWithoutTheRoutePermission() throws Exception {
        PlatformAuthenticatedSession developer = sessionWith(
                new PlatformPermissionGrant("platform:read", "GLOBAL", "*"));
        MockHttpServletResponse denied = new MockHttpServletResponse();

        assertFalse(admitsBearer(developer, "POST", "/api/tool-acl", denied));
        assertEquals(403, denied.getStatus());
        assertTrue(denied.getContentAsString().contains(PlatformConsoleAuthInterceptor.PLATFORM_PERMISSION_DENIED));
        assertTrue(denied.getContentAsString().contains("tool-acl:manage"));
        assertFalse(admitsBearer(developer, "GET", "/ai/knowledge/base/list", new MockHttpServletResponse()));
        assertFalse(admitsBearer(developer, "POST", "/model/chat", new MockHttpServletResponse()));
        assertTrue(admitsBearer(developer, "GET", "/api/scan-projects", new MockHttpServletResponse()));
    }

    @Test
    void separatesReadAndWritePermissionsOfARoute() {
        PlatformAuthenticatedSession reader = sessionWith(
                new PlatformPermissionGrant("knowledge:read", "GLOBAL", "*"));

        assertTrue(admitsBearer(reader, "GET", "/ai/knowledge/base/list", new MockHttpServletResponse()));
        assertTrue(admitsBearer(reader, "POST", "/ai/retrieval/test", new MockHttpServletResponse()));
        assertFalse(admitsBearer(reader, "DELETE", "/ai/knowledge/base/kb1", new MockHttpServletResponse()));
    }

    @Test
    void limitsRegistryInstanceRoutesToTheGrantedProject() {
        PlatformAuthenticatedSession orders = sessionWith(
                new PlatformPermissionGrant("platform:read", "PROJECT", "orders"),
                new PlatformPermissionGrant("platform:write", "PROJECT", "orders"));

        assertTrue(admitsBearer(orders, "GET", "/api/registry/projects/orders/instances",
                new MockHttpServletResponse()));
        assertTrue(admitsBearer(orders, "POST", "/api/registry/projects/orders/instances/status",
                new MockHttpServletResponse()));
        assertFalse(admitsBearer(orders, "GET", "/api/registry/projects/billing/instances",
                new MockHttpServletResponse()));
    }

    @Test
    void deniesAProtectedRouteThatHasNoDeclaredPermission() {
        assertFalse(admitsBearer(authenticatedSession("pls_all"), "GET", "/api/unclassified/probe",
                new MockHttpServletResponse()));
    }

    @RestController
    private static class ProtectedProbeController {

        @GetMapping("/api/workflows/probe")
        String probe() {
            return "unexpected";
        }
    }
}
