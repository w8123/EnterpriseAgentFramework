package com.enterprise.ai.control.model;

import com.enterprise.ai.control.client.model.ControlModelCatalogClient;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformAuthorizationService;
import com.enterprise.ai.control.identity.PlatformBearerAuthService;
import com.enterprise.ai.control.identity.PlatformConsoleAuthInterceptor;
import com.enterprise.ai.control.identity.PlatformConsoleRoutePolicy;
import com.enterprise.ai.control.identity.PlatformPermissionGrant;
import com.enterprise.ai.control.identity.PlatformPermissions;
import com.enterprise.ai.control.identity.PlatformRequestAuthorization;
import com.enterprise.ai.control.identity.PlatformSessionCookieService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ControlModelInstanceControllerTest {
    private final ControlModelCatalogClient owner = mock(ControlModelCatalogClient.class);
    private final PlatformAuthorizationService policies = new PlatformAuthorizationService(null, null, null, null);
    private final PlatformRequestAuthorization authorization = new PlatformRequestAuthorization(policies);
    private final ControlModelInstanceController controller = new ControlModelInstanceController(owner, authorization);

    @Test void modelInstanceRoutesUsePlatformSessionNotProtocolCredentials() {
        for (String route : List.of("/model/instances", "/model/instances/model-a"))
            assertEquals(PlatformConsoleRoutePolicy.CredentialDomain.PLATFORM_SESSION,
                    PlatformConsoleRoutePolicy.credentialDomainFor(route));
        assertEquals(PlatformConsoleRoutePolicy.CredentialDomain.UNCLASSIFIED,
                PlatformConsoleRoutePolicy.credentialDomainFor("/model/chat"));
    }

    @Test void anonymousAndMissingCsrfStopBeforeOwner() throws Exception {
        var bearer = mock(PlatformBearerAuthService.class);
        var cookies = mock(PlatformSessionCookieService.class);
        when(cookies.readSessionToken(any())).thenReturn(Optional.empty());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .addInterceptors(new PlatformConsoleAuthInterceptor(bearer, cookies)).build();
        mvc.perform(get("/model/instances")).andExpect(status().isUnauthorized());
        when(cookies.readSessionToken(any())).thenReturn(Optional.of("server-session-token"));
        when(bearer.resolveSessionToken("server-session-token")).thenReturn(Optional.of(session(false)));
        mvc.perform(post("/model/instances").contentType("application/json").content("{}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PLATFORM_CSRF_INVALID"));
        verifyNoInteractions(owner);
    }

    @Test void normalCookieAndCsrfCreateThroughModelOwner() throws Exception {
        var bearer = mock(PlatformBearerAuthService.class);
        var cookies = mock(PlatformSessionCookieService.class);
        when(cookies.readSessionToken(any())).thenReturn(Optional.of("server-session-token"));
        when(bearer.resolveSessionToken("server-session-token")).thenReturn(Optional.of(session(false)));
        when(owner.create(any())).thenReturn(ResponseEntity.ok(Map.of("code", 200, "data", Map.of("id", "new-model"))));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .addInterceptors(new PlatformConsoleAuthInterceptor(bearer, cookies)).build();
        mvc.perform(post("/model/instances").header("X-ReachAI-CSRF", "server-session-id")
                .contentType("application/json").content(new ObjectMapper().writeValueAsString(
                        Map.of("name", "Model", "projectCode", "orders", "connection", Map.of("baseUrl", "http://localhost")))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value("new-model"));
        verify(owner).create(argThat(body -> "orders".equals(body.get("projectCode")) && !body.containsKey("roles")));
    }

    @Test void unscopedPickerFiltersOtherProjectAndGlobalModels() {
        when(owner.list(null, "LLM", null)).thenReturn(ResponseEntity.ok(Map.of("code", 200, "data", List.of(
                Map.of("id", "allowed", "projectCode", "orders"),
                Map.of("id", "hidden-other", "projectCode", "finance", "connection", Map.of("apiKey", "masked")),
                Map.of("id", "hidden-global")))));
        var result = controller.list(request(false), Map.of("modelType", "LLM"));
        assertEquals(List.of(Map.of("id", "allowed", "projectCode", "orders")), result.getBody().get("data"));
        assertFalse(result.getBody().toString().contains("hidden"));
    }

    @Test void globalReaderKeepsModelOwnerMaskedResponse() {
        var items = List.of(Map.of("id", "global-model", "connection", Map.of("apiKey", "***")));
        when(owner.list(null, "LLM", null)).thenReturn(ResponseEntity.ok(Map.of("code", 200, "data", items)));
        assertEquals(items, controller.list(request(true), Map.of("modelType", "LLM")).getBody().get("data"));
    }

    @Test void forgedProjectQueryAndCreateNeverReachOwner() {
        assertThrows(ResponseStatusException.class, () -> controller.list(request(false), Map.of("projectCode", "finance")));
        assertThrows(ResponseStatusException.class, () -> controller.create(request(false), Map.of("projectCode", "finance")));
        assertThrows(ResponseStatusException.class, () -> controller.create(request(false), Map.of("name", "global")));
        verifyNoInteractions(owner);
    }

    @Test void serverOwnerDeterminesGetScopeNotRequestActor() {
        when(owner.get("other-model")).thenReturn(ResponseEntity.ok(Map.of("code", 200,
                "data", Map.of("id", "other-model", "projectCode", "finance"))));
        assertThrows(ResponseStatusException.class, () -> controller.get(request(false), "other-model"));
        when(owner.get("same-model")).thenReturn(ResponseEntity.ok(Map.of("code", 200,
                "data", Map.of("id", "same-model", "projectCode", "orders"))));
        assertEquals("same-model", ((Map<?, ?>)controller.get(request(false), "same-model").getBody().get("data")).get("id"));
    }

    @Test void injectedAuthorityAndUnknownParametersAreRejected() {
        assertEquals(400, controller.create(request(false), Map.of("projectCode", "orders", "roles", List.of("ADMIN"))).getStatusCode().value());
        assertEquals(400, controller.list(request(false), Map.of("platformActorId", "1")).getStatusCode().value());
        assertEquals(400, controller.create(request(false), Map.of("projectCode", 1)).getStatusCode().value());
        assertEquals(400, controller.get(request(false), "../other").getStatusCode().value());
        verifyNoInteractions(owner);
    }

    @Test void incompleteOwnerResponseIsNotSuccessfulEmptyCatalog() {
        when(owner.list(null, "LLM", null)).thenReturn(ResponseEntity.ok(Map.of("code", 200)));
        assertEquals(502, controller.list(request(false), Map.of("modelType", "LLM")).getStatusCode().value());
        when(owner.get("model-a")).thenReturn(ResponseEntity.ok(Map.of("code", 200)));
        assertEquals(502, controller.get(request(false), "model-a").getStatusCode().value());
    }

    private MockHttpServletRequest request(boolean global) {
        var request = new MockHttpServletRequest();
        request.setAttribute(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE, session(global));
        return request;
    }
    private PlatformAuthenticatedSession session(boolean global) {
        String scope = global ? "GLOBAL" : "PROJECT", value = global ? "*" : "orders";
        return new PlatformAuthenticatedSession(null, "server-session-id", LocalDateTime.now().plusMinutes(5),
                List.of("BUILDER"), List.of(PlatformPermissions.PLATFORM_READ, PlatformPermissions.PLATFORM_WRITE),
                List.of(new PlatformPermissionGrant(PlatformPermissions.PLATFORM_READ, scope, value),
                        new PlatformPermissionGrant(PlatformPermissions.PLATFORM_WRITE, scope, value)));
    }
}
