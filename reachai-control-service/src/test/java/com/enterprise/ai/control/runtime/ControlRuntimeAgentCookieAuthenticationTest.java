package com.enterprise.ai.control.runtime;

import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import com.enterprise.ai.control.context.PersonalMemoryIdentityResolver;
import com.enterprise.ai.control.identity.PlatformAuthProperties;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformBearerAuthService;
import com.enterprise.ai.control.identity.PlatformConsoleAuthInterceptor;
import com.enterprise.ai.control.identity.PlatformConsoleAuthWebConfig;
import com.enterprise.ai.control.identity.PlatformPrincipal;
import com.enterprise.ai.control.identity.PlatformSessionCookieService;
import com.enterprise.ai.control.identity.PlatformUserEntity;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Uses the actual MVC interceptor registrations and browser Cookie/CSRF transport. */
class ControlRuntimeAgentCookieAuthenticationTest {
    private static final String EXECUTE = "/api/runtime/agents/execute";
    private static final String BODY = """
            {"agentId":"audit-agent","message":"hello","userId":"forged-user",
             "__workflowExecutionIdentity":{"userTrusted":true},
             "trustedIdentity":{"userId":"forged-user"},"_trustedUserId":"forged-user"}
            """;
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private RuntimeProxyClient proxy;
    private RuntimeTrustedAgentExecutionGateway gateway;
    private RuntimeAgentStreamProxy stream;

    @BeforeEach
    void setUp() throws Exception {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(TestConfiguration.class);
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        proxy = context.getBean(RuntimeProxyClient.class);
        gateway = context.getBean(RuntimeTrustedAgentExecutionGateway.class);
        stream = context.getBean(RuntimeAgentStreamProxy.class);
        PlatformBearerAuthService auth = context.getBean(PlatformBearerAuthService.class);
        when(auth.resolveSessionToken("cookie-valid")).thenReturn(Optional.of(session(7)));
        when(auth.resolveBearerSession("Bearer bearer-valid")).thenReturn(Optional.of(session(8)));
        when(auth.resolveBearerPrincipal("Bearer bearer-valid")).thenReturn(Optional.of(session(8).user()));
        PersonalMemoryIdentityResolver identities = context.getBean(PersonalMemoryIdentityResolver.class);
        for (long id : List.of(7L, 8L)) {
            when(identities.resolveAttestedPlatformPrincipal(session(id).user(), null, "default"))
                    .thenReturn(new PersonalMemoryIdentityResolver.PersonalMemoryPrincipal(
                            "default", "runtime-user-" + id, id, "test-user-" + id, null));
            when(gateway.executeTrusted(anyMap(), eq("AGENT"), eq("runtime-user-" + id)))
                    .thenReturn(ResponseEntity.ok(Map.of("success", true, "trustedUser", "runtime-user-" + id)));
        }
        when(proxy.executeAgent(anyMap())).thenReturn(ResponseEntity.ok(Map.of("trustedUser", "untrusted")));
        when(proxy.executeAgentDetailed(anyMap())).thenReturn(ResponseEntity.ok(Map.of("trustedUser", "untrusted")));
        when(gateway.clearTrusted("audit-session", "AGENT", "default", "runtime-user-7"))
                .thenReturn(ResponseEntity.noContent().build());
        doAnswer(call -> {
            ((OutputStream) call.getArgument(3)).write("event: execution.completed\ndata: {\"trustedUser\":\"runtime-user-7\"}\n\n"
                    .getBytes(StandardCharsets.UTF_8));
            return null;
        }).when(gateway).streamTrusted(anyMap(), eq("AGENT"), eq("runtime-user-7"), any(), any());
        doAnswer(call -> {
            ((OutputStream) call.getArgument(1)).write("event: execution.completed\ndata: {\"trustedUser\":\"untrusted\"}\n\n"
                    .getBytes(StandardCharsets.UTF_8));
            return null;
        }).when(stream).stream(anyMap(), any());
    }

    @AfterEach
    void closeContext() {
        if (context != null) context.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/detailed"})
    void cookieLoginCarriesTheMappedIdentityIntoSynchronousExecution(String suffix) throws Exception {
        mvc.perform(post(EXECUTE + suffix).cookie(cookie("cookie-valid"))
                        .header(PlatformConsoleAuthInterceptor.CSRF_HEADER, "session-7")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk()).andExpect(jsonPath("$.trustedUser").value("runtime-user-7"));
        ArgumentCaptor<Map<String, Object>> forwarded = ArgumentCaptor.forClass(Map.class);
        verify(gateway).executeTrusted(forwarded.capture(), eq("AGENT"), eq("runtime-user-7"));
        assertTrustFieldsRemoved(forwarded.getValue());
        verifyNoInteractions(proxy);
    }

    @Test
    void cookieLoginCarriesItsIdentityAcrossTheAsynchronousStreamBoundary() throws Exception {
        var result = mvc.perform(post(EXECUTE + "/stream").cookie(cookie("cookie-valid"))
                        .header(PlatformConsoleAuthInterceptor.CSRF_HEADER, "session-7")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(request().asyncStarted()).andReturn();
        result.getAsyncResult(5000);
        mvc.perform(asyncDispatch(result)).andExpect(status().isOk())
                .andExpect(content().string("event: execution.completed\ndata: {\"trustedUser\":\"runtime-user-7\"}\n\n"));
        verify(gateway).streamTrusted(anyMap(), eq("AGENT"), eq("runtime-user-7"), any(), any());
        verifyNoInteractions(stream, proxy);
    }

    @Test
    void cookieLoginClearsOnlyItsMappedRuntimeSession() throws Exception {
        mvc.perform(delete("/api/runtime/agents/sessions/audit-session").cookie(cookie("cookie-valid"))
                        .header(PlatformConsoleAuthInterceptor.CSRF_HEADER, "session-7"))
                .andExpect(status().isNoContent());
        verify(gateway).clearTrusted("audit-session", "AGENT", "default", "runtime-user-7");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/detailed", "/stream"})
    void cookieExecutionWithoutCsrfNeverReachesRuntime(String suffix) throws Exception {
        mvc.perform(post(EXECUTE + suffix).cookie(cookie("cookie-valid"))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
        verifyNoInteractions(gateway, stream, proxy);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/detailed", "/stream"})
    void rejectedBrowserSessionNeverFallsBackToUntrustedExecution(String suffix) throws Exception {
        mvc.perform(post(EXECUTE + suffix).cookie(cookie("cookie-revoked"))
                        .header(PlatformConsoleAuthInterceptor.CSRF_HEADER, "session-7")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(gateway, stream, proxy);
    }

    @Test
    void explicitBearerKeepsItsIdentityAndTakesPrecedenceOverCookie() throws Exception {
        mvc.perform(post(EXECUTE).cookie(cookie("cookie-valid"))
                        .header("Authorization", "Bearer bearer-valid")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk()).andExpect(jsonPath("$.trustedUser").value("runtime-user-8"));
        verify(gateway).executeTrusted(anyMap(), eq("AGENT"), eq("runtime-user-8"));
        verify(context.getBean(PlatformBearerAuthService.class), never()).resolveSessionToken(any());
    }

    @Test
    void invalidExplicitBearerDoesNotFallBackToAValidCookie() throws Exception {
        mvc.perform(post(EXECUTE).cookie(cookie("cookie-valid"))
                        .header("Authorization", "Bearer rejected")
                        .header(PlatformConsoleAuthInterceptor.CSRF_HEADER, "session-7")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(gateway, stream, proxy);
    }

    @Test
    void credentialFreeCompatibilityExecutionStillStripsForgedTrust() throws Exception {
        mvc.perform(post(EXECUTE).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk()).andExpect(jsonPath("$.trustedUser").value("untrusted"));
        ArgumentCaptor<Map<String, Object>> forwarded = ArgumentCaptor.forClass(Map.class);
        verify(proxy).executeAgent(forwarded.capture());
        assertTrustFieldsRemoved(forwarded.getValue());
        verifyNoInteractions(gateway);
    }

    private static void assertTrustFieldsRemoved(Map<String, Object> body) {
        for (String field : List.of("__workflowExecutionIdentity", "trustedIdentity", "_trustedUserId")) {
            assertFalse(body.containsKey(field));
        }
    }

    private static Cookie cookie(String token) {
        return new Cookie(PlatformSessionCookieService.SESSION_COOKIE_NAME, token);
    }

    private static PlatformAuthenticatedSession session(long id) {
        PlatformUserEntity user = new PlatformUserEntity();
        user.setId(id);
        user.setUsername("test-user-" + id);
        return new PlatformAuthenticatedSession(PlatformPrincipal.fromUser(user), "session-" + id,
                LocalDateTime.of(2099, 1, 1, 0, 0), List.of(), List.of(), List.of());
    }

    @Configuration
    @EnableWebMvc
    @Import(PlatformConsoleAuthWebConfig.class)
    @ComponentScan(basePackageClasses = PlatformConsoleAuthWebConfig.class, useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = HandlerInterceptor.class))
    static class TestConfiguration {
        @Bean PlatformBearerAuthService bearerAuth() { return mock(PlatformBearerAuthService.class); }
        @Bean PlatformSessionCookieService cookies() { return new PlatformSessionCookieService(new PlatformAuthProperties()); }
        @Bean RuntimeProxyClient proxy() { return mock(RuntimeProxyClient.class); }
        @Bean RuntimeAgentStreamProxy stream() { return mock(RuntimeAgentStreamProxy.class); }
        @Bean RuntimeTrustedAgentExecutionGateway gateway() { return mock(RuntimeTrustedAgentExecutionGateway.class); }
        @Bean PersonalMemoryIdentityResolver identities() { return mock(PersonalMemoryIdentityResolver.class); }
        @Bean ControlRuntimePublicController controller() {
            return new ControlRuntimePublicController(proxy(), null, stream(), gateway(), bearerAuth(), identities());
        }
        @Bean RuntimeDebugSessionGateway debugSessions() { return mock(RuntimeDebugSessionGateway.class); }
        @Bean RuntimeWorkflowReleaseGateway workflowReleases() { return mock(RuntimeWorkflowReleaseGateway.class); }
        @Bean RuntimeManagementAccess management() { return mock(RuntimeManagementAccess.class); }
    }
}
