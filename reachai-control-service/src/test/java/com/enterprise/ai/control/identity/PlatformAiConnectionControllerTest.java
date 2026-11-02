package com.enterprise.ai.control.identity;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PlatformAiConnectionControllerTest {
    @Test
    void connectionCreationRequiresTheOwnersLoginAndCsrfProof() throws Exception {
        PlatformAiConnectionService connections = mock(PlatformAiConnectionService.class);
        PlatformBearerAuthService bearer = mock(PlatformBearerAuthService.class);
        PlatformUserEntity user = new PlatformUserEntity();
        user.setId(7L);
        PlatformAuthenticatedSession actor = new PlatformAuthenticatedSession(PlatformPrincipal.fromUser(user),
                "pls_7", LocalDateTime.now().plusHours(1), List.of(), List.of(), List.of());
        when(bearer.resolveSessionToken("browser-session")).thenReturn(Optional.of(actor));
        PlatformAiConnectionService.ConnectionView view = new PlatformAiConnectionService.ConnectionView(
                19L, "Codex", "CODEX", "rpi_hint", "ACTIVE", null, null, null, null);
        when(connections.create(eq(actor), any())).thenReturn(
                new PlatformAiConnectionService.CreatedConnection(view, "rpi_once"));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new PlatformAiConnectionController(connections,
                        new PlatformRequestAuthorization(mock(PlatformAuthorizationService.class))))
                .addInterceptors(new PlatformConsoleAuthInterceptor(bearer,
                        new PlatformSessionCookieService(new PlatformAuthProperties())))
                .build();
        Cookie cookie = new Cookie(PlatformSessionCookieService.SESSION_COOKIE_NAME, "browser-session");
        String json = "{\"label\":\"Codex\",\"toolKind\":\"CODEX\",\"ttlDays\":1}";

        mvc.perform(get("/api/me/ai-connections")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/me/ai-connections").cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isForbidden());
        verify(connections, never()).create(any(), any());
        mvc.perform(post("/api/me/ai-connections").cookie(cookie)
                        .header(PlatformConsoleAuthInterceptor.CSRF_HEADER, "pls_7")
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.connection.id").value(19))
                .andExpect(jsonPath("$.token").value("rpi_once"));
        verify(connections).create(eq(actor), any());
    }
}
