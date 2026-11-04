package com.enterprise.ai.model.security;

import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.common.internalauth.ModelServiceRequestAuth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ModelInternalAuthFilterTest {
    private static final String SECRET = "model-internal-auth-fixture-secret-1234567890";
    private static final String CONTROL = InternalServiceAuthHeaders.CALLER_CONTROL;
    private static final String RUNTIME = InternalServiceAuthHeaders.CALLER_RUNTIME;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        ModelInternalNonceStore store = mock(ModelInternalNonceStore.class);
        Set<String> consumed = new HashSet<>();
        when(store.tryConsume(anyString(), anyString(), any())).thenAnswer(call -> consumed.add(call.getArgument(1)));
        mvc = MockMvcBuilders.standaloneSetup(new FixtureController()).addFilters(
                new ModelInternalAuthFilter(SECRET, "", 300, 1024, store, new MockEnvironment())).build();
    }

    @Test
    void rejectsDirectBrowserAndForgedServiceAccessButAllowsHealth() throws Exception {
        for (String path : new String[]{"/model/instances", "/internal/model/instances", "/model/templates", "/model/instances;ignored"}) {
            mvc.perform(get(path).header(InternalServiceAuthHeaders.CALLER, CONTROL))
                    .andExpect(status().isUnauthorized()).andExpect(content().string("{\"code\":\"MODEL_INTERNAL_AUTH_REQUIRED\"}"));
        }
        mvc.perform(post("/model/chat").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void signsBodyAndRawQueryAndConsumesNonceOnce() throws Exception {
        String target = "/model/instances?provider=custom&keyword=a%20b";
        HttpHeaders headers = signed(CONTROL, "GET", target, "");
        mvc.perform(get(java.net.URI.create(target)).headers(headers)).andExpect(status().isOk());
        mvc.perform(get(java.net.URI.create(target)).headers(headers)).andExpect(status().isUnauthorized());
        mvc.perform(get(java.net.URI.create(target + "&includeArchived=true")).headers(signed(CONTROL, "GET", target, "")))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/model/chat").headers(signed(RUNTIME, "POST", "/model/chat", "{}"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"changed\":true}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void signatureIncludesAnOptionalServletContextPrefix() throws Exception {
        String target = "/gateway/model/instances";
        mvc.perform(get(target).contextPath("/gateway").headers(signed(CONTROL, "GET", target, "")))
                .andExpect(status().isOk());
        mvc.perform(get(target).contextPath("/gateway").headers(signed(CONTROL, "GET", "/model/instances", "")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void runtimeAndKnowledgeMayExecuteButCannotManageModelAccounts() throws Exception {
        for (String caller : new String[]{RUNTIME, ModelServiceRequestAuth.CALLER_KNOWLEDGE}) {
            mvc.perform(post("/model/chat").headers(signed(caller, "POST", "/model/chat", "{}"))
                    .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isOk()).andExpect(content().string("{}"));
            mvc.perform(get("/model/instances").headers(signed(caller, "GET", "/model/instances", "")))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(get("/internal/model/instances").headers(signed(RUNTIME, "GET", "/internal/model/instances", "")))
                .andExpect(status().isOk());
    }

    @Test
    void rejectsOversizedBodyExpiredTimestampAndUnknownCallers() throws Exception {
        String oversized = "x".repeat(1025);
        mvc.perform(post("/model/chat").headers(signed(CONTROL, "POST", "/model/chat", oversized)).content(oversized))
                .andExpect(status().isUnauthorized());
        HttpHeaders expired = signed(CONTROL, "GET", "/model/instances", "");
        expired.set(InternalServiceAuthHeaders.TIMESTAMP, "1");
        mvc.perform(get("/model/instances").headers(expired)).andExpect(status().isUnauthorized());
        mvc.perform(get("/model/instances").headers(signed("browser", "GET", "/model/instances", "")))
                .andExpect(status().isUnauthorized());
    }

    private static HttpHeaders signed(String caller, String method, String target, String body) {
        HttpHeaders headers = new HttpHeaders();
        ModelServiceRequestAuth.sign(SECRET, caller, method, target, body.getBytes(StandardCharsets.UTF_8)).forEach(headers::set);
        return headers;
    }

    @RestController
    static class FixtureController {
        @GetMapping({"/model/instances", "/internal/model/instances", "/actuator/health"})
        Map<String, Object> get() { return Map.of("ok", true); }
        @PostMapping("/model/chat")
        String chat(@RequestBody String body) { return body; }
    }
}
