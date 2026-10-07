package com.enterprise.ai.control.capability;

import com.enterprise.ai.common.capability.ConsoleCapabilityInvocationContracts;
import com.enterprise.ai.control.governance.ControlToolAclDecisionService;
import com.enterprise.ai.control.governance.ControlToolAclEntity;
import com.enterprise.ai.control.governance.ControlToolAclMapper;
import com.enterprise.ai.control.identity.PlatformAuthProperties;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformAuthorizationService;
import com.enterprise.ai.control.identity.PlatformBearerAuthService;
import com.enterprise.ai.control.identity.PlatformConsoleAuthInterceptor;
import com.enterprise.ai.control.identity.PlatformConsoleAuthWebConfig;
import com.enterprise.ai.control.identity.PlatformOptionalSessionAuthInterceptor;
import com.enterprise.ai.control.identity.PlatformPermissionGrant;
import com.enterprise.ai.control.identity.PlatformPermissions;
import com.enterprise.ai.control.identity.PlatformPrincipal;
import com.enterprise.ai.control.identity.PlatformRequestAuthorization;
import com.enterprise.ai.control.identity.PlatformRoleMapper;
import com.enterprise.ai.control.identity.PlatformRolePermissionMapper;
import com.enterprise.ai.control.identity.PlatformSessionCookieService;
import com.enterprise.ai.control.identity.PlatformUserEntity;
import com.enterprise.ai.control.identity.PlatformUserRoleMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the public trial-call route through the real platform-session MVC
 * registration.  The two remote gateways are deliberately mocked only after
 * the browser/session/authorization boundary so each rejection can prove zero
 * Runtime invocation without requiring a live service.
 */
class CapabilityInvocationConsoleMvcSecurityTest {

    private static final String PATH = "/api/business-methods/orders.lookup/invocations";
    private static final String HASH = "a".repeat(64);
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private PlatformBearerAuthService auth;
    private CapabilityReviewGateway capability;
    private RuntimeConsoleCapabilityInvocationGateway runtime;
    private ControlToolAclMapper aclMapper;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(TestConfiguration.class);
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        auth = context.getBean(PlatformBearerAuthService.class);
        capability = context.getBean(CapabilityReviewGateway.class);
        runtime = context.getBean(RuntimeConsoleCapabilityInvocationGateway.class);
        aclMapper = context.getBean(ControlToolAclMapper.class);
        configureAllowedActor("cookie-allowed", 7L, "orders");
    }

    @AfterEach
    void closeContext() {
        if (context != null) context.close();
    }

    @Test
    void unauthenticatedOrMissingCsrfBrowserPostsNeverReachEitherGateway() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(validBody()))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(capability, runtime);

        mvc.perform(post(PATH).cookie(cookie("cookie-allowed"))
                        .contentType(MediaType.APPLICATION_JSON).content(validBody()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(capability, runtime);
    }

    @Test
    void missingInvokePermissionAndCrossProjectScopeCannotDispatch() throws Exception {
        when(auth.resolveSessionToken("cookie-no-invoke"))
                .thenReturn(Optional.of(session(11L, "no-invoke", List.of(PlatformPermissions.PLATFORM_READ), "orders")));
        mvc.perform(authorizedPost("cookie-no-invoke", validBody()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(capability, runtime);

        reset(capability, runtime);
        configureAllowedActor("cookie-other-project", 12L, "other");
        mvc.perform(authorizedPost("cookie-other-project", validBody()))
                .andExpect(status().isForbidden());
        verify(capability).getBusinessMethodInvocationContext("orders.lookup", "12");
        verifyNoInteractions(runtime);
    }

    @Test
    void aclRevocationOtherActorAndForgedTopLevelFactsCannotDispatch() throws Exception {
        when(aclMapper.selectList(any())).thenReturn(List.of());
        mvc.perform(authorizedPost("cookie-allowed", validBody()))
                .andExpect(status().isForbidden());
        verify(capability).getBusinessMethodInvocationContext("orders.lookup", "7");
        verifyNoInteractions(runtime);

        reset(capability, runtime, aclMapper);
        when(auth.resolveSessionToken("cookie-revoked")).thenReturn(Optional.empty());
        mvc.perform(authorizedPost("cookie-revoked", validBody()))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(capability, runtime);

        configureAllowedActor("cookie-other-actor", 8L, "orders");
        when(capability.getBusinessMethodInvocationContext("orders.lookup", "8"))
                .thenReturn(ResponseEntity.notFound().build());
        mvc.perform(authorizedPost("cookie-other-actor", validBody()))
                .andExpect(status().isNotFound());
        verifyNoInteractions(runtime);

        reset(capability, runtime);
        mvc.perform(authorizedPost("cookie-allowed", """
                        {"invocationId":"00000000-0000-0000-0000-000000000001",
                         "expectedContractHash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                         "expectedExecutionRevision":"cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                         "input":{},"confirmedSideEffect":false,
                         "projectId":999,"qualifiedName":"forged.method","platformActorId":"999"}
                        """))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(capability, runtime);
    }

    @Test
    void validCookieCsrfAndOwnerContextBuildAnActorAttestedRuntimeCommand() throws Exception {
        when(runtime.invoke(any(), eq("7"))).thenAnswer(call -> {
            ConsoleCapabilityInvocationContracts.InvocationCommand command = call.getArgument(0);
            return ResponseEntity.ok(outcome(command));
        });

        mvc.perform(authorizedPost("cookie-allowed", validBody()))
                .andExpect(status().isOk());

        ArgumentCaptor<ConsoleCapabilityInvocationContracts.InvocationCommand> command =
                ArgumentCaptor.forClass(ConsoleCapabilityInvocationContracts.InvocationCommand.class);
        verify(runtime).invoke(command.capture(), eq("7"));
        assertEquals(7L, command.getValue().projectId());
        assertEquals("orders", command.getValue().projectCode());
        assertEquals("orders.lookup", command.getValue().qualifiedName());
        assertEquals("c".repeat(64), command.getValue().expectedExecutionRevision());
        assertFalse(command.getValue().input().containsKey("platformActorId"));
    }

    private void configureAllowedActor(String token, long actorId, String grantedProject) {
        when(auth.resolveSessionToken(token)).thenReturn(Optional.of(session(actorId, "session-" + actorId,
                List.of(PlatformPermissions.PLATFORM_READ, PlatformPermissions.CAPABILITY_INVOKE), grantedProject)));
        when(capability.getBusinessMethodInvocationContext("orders.lookup", String.valueOf(actorId)))
                .thenReturn(ResponseEntity.ok(context()));
        ControlToolAclEntity rule = new ControlToolAclEntity();
        rule.setRoleCode("OPERATOR");
        rule.setProjectId(7L);
        rule.setProjectCode("orders");
        rule.setTargetKind("TOOL");
        rule.setTargetName("orders.lookup");
        rule.setPermission("ALLOW");
        rule.setEnabled(true);
        when(aclMapper.selectList(any())).thenReturn(List.of(rule));
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authorizedPost(
            String token, String body) {
        return post(PATH).cookie(cookie(token))
                .header(PlatformConsoleAuthInterceptor.CSRF_HEADER, sessionId(token))
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static Cookie cookie(String token) {
        return new Cookie(PlatformSessionCookieService.SESSION_COOKIE_NAME, token);
    }

    private static String sessionId(String token) {
        return switch (token) {
            case "cookie-allowed" -> "session-7";
            case "cookie-no-invoke" -> "no-invoke";
            case "cookie-other-project" -> "session-12";
            case "cookie-other-actor" -> "session-8";
            default -> "revoked";
        };
    }

    private static PlatformAuthenticatedSession session(long id,
                                                        String sessionId,
                                                        List<String> permissions,
                                                        String project) {
        PlatformUserEntity user = new PlatformUserEntity();
        user.setId(id);
        user.setUsername("actor-" + id);
        return new PlatformAuthenticatedSession(PlatformPrincipal.fromUser(user), sessionId,
                LocalDateTime.of(2099, 1, 1, 0, 0), List.of("OPERATOR"), permissions,
                List.of(new PlatformPermissionGrant(PlatformPermissions.PLATFORM_READ, "PROJECT", project),
                        new PlatformPermissionGrant(PlatformPermissions.CAPABILITY_INVOKE, "PROJECT", project)));
    }

    private static String validBody() {
        return """
                {"invocationId":"00000000-0000-0000-0000-000000000001",
                 "expectedContractHash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                 "expectedExecutionRevision":"cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                 "input":{"orderNo":"O-1"},"confirmedSideEffect":false}
                """;
    }

    private static Map<String, Object> context() {
        return Map.ofEntries(
                Map.entry("contractVersion", 1), Map.entry("name", "orders.lookup"),
                Map.entry("qualifiedName", "orders.lookup"), Map.entry("sourceQualifiedName", "orders.lookup"),
                Map.entry("assetType", "BUSINESS_METHOD"), Map.entry("projectId", 7L),
                Map.entry("projectCode", "orders"), Map.entry("currentContractHash", HASH),
                Map.entry("acceptedContractHash", HASH), Map.entry("sourceContractHash", HASH),
                Map.entry("sourceAvailability", "READY"), Map.entry("enabled", true),
                Map.entry("sideEffect", "READ_ONLY"), Map.entry("parameters", List.of()),
                Map.entry("requestBodyType", "json"), Map.entry("responseType", "json"),
                Map.entry("targetDescription", "accepted contract"), Map.entry("targetInstanceStatus", "READY"),
                Map.entry("credentialAvailable", true), Map.entry("businessIdentityRequired", false),
                Map.entry("executionRevision", "c".repeat(64)),
                Map.entry("executable", true), Map.entry("timeoutMs", 1_000L));
    }

    private static Map<String, Object> outcome(ConsoleCapabilityInvocationContracts.InvocationCommand command) {
        return Map.ofEntries(
                Map.entry("contractVersion", 1), Map.entry("invocationId", command.invocationId()),
                Map.entry("runId", 1L), Map.entry("traceId", "trace-1"),
                Map.entry("projectId", command.projectId()), Map.entry("projectCode", command.projectCode()),
                Map.entry("qualifiedName", command.qualifiedName()),
                Map.entry("identityMode", "PROJECT_CREDENTIAL_NO_BUSINESS_IDENTITY"),
                Map.entry("status", "SUCCEEDED"), Map.entry("dispatchStage", "CONFIRMED"),
                Map.entry("terminal", true));
    }

    @Configuration
    @EnableWebMvc
    @Import(PlatformConsoleAuthWebConfig.class)
    static class TestConfiguration {
        @Bean PlatformBearerAuthService auth() { return mock(PlatformBearerAuthService.class); }
        @Bean PlatformSessionCookieService cookies() { return new PlatformSessionCookieService(new PlatformAuthProperties()); }
        @Bean PlatformConsoleAuthInterceptor interceptor() { return new PlatformConsoleAuthInterceptor(auth(), cookies()); }
        @Bean PlatformOptionalSessionAuthInterceptor optionalInterceptor() {
            return new PlatformOptionalSessionAuthInterceptor(interceptor(), cookies());
        }
        @Bean PlatformUserRoleMapper userRoles() { return mock(PlatformUserRoleMapper.class); }
        @Bean PlatformRoleMapper roles() { return mock(PlatformRoleMapper.class); }
        @Bean PlatformRolePermissionMapper rolePermissions() { return mock(PlatformRolePermissionMapper.class); }
        @Bean com.enterprise.ai.control.identity.PlatformPermissionMapper permissions() {
            return mock(com.enterprise.ai.control.identity.PlatformPermissionMapper.class);
        }
        @Bean PlatformAuthorizationService platformAuthorization() {
            return new PlatformAuthorizationService(userRoles(), roles(), rolePermissions(), permissions());
        }
        @Bean PlatformRequestAuthorization authorization() { return new PlatformRequestAuthorization(platformAuthorization()); }
        @Bean ControlToolAclMapper aclMapper() { return mock(ControlToolAclMapper.class); }
        @Bean ControlToolAclDecisionService toolAcl() { return new ControlToolAclDecisionService(aclMapper()); }
        @Bean CapabilityReviewGateway capability() { return mock(CapabilityReviewGateway.class); }
        @Bean RuntimeConsoleCapabilityInvocationGateway runtime() {
            return mock(RuntimeConsoleCapabilityInvocationGateway.class);
        }
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
        @Bean CapabilityInvocationConsoleController controller() {
            return new CapabilityInvocationConsoleController(capability(), runtime(), authorization(), toolAcl(), objectMapper());
        }
    }
}
