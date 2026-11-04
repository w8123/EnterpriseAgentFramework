package com.enterprise.ai.control.compat;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformConsoleAuthInterceptor;
import com.enterprise.ai.control.identity.PlatformPermissionGrant;
import com.enterprise.ai.control.identity.PlatformPrincipal;
import com.enterprise.ai.control.mcp.application.port.McpPlatformModelCatalogPort;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestTemplate;

import java.net.http.HttpClient;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ModelInstanceConsoleScopeTest {
    private final RestTemplate http = new RestTemplate();
    private final MockRestServiceServer model = MockRestServiceServer.createServer(http);
    private final McpPlatformModelCatalogPort catalog = mock(McpPlatformModelCatalogPort.class);
    private final PlatformAuthenticatedSession session = new PlatformAuthenticatedSession(new PlatformPrincipal(7L, "builder", "Builder"),
            "console", LocalDateTime.now().plusHours(1), List.of(), List.of("model:read", "model:manage"),
            List.of(new PlatformPermissionGrant("model:read", "GLOBAL", "*"), new PlatformPermissionGrant("model:manage", "PROJECT", "orders")));
    private final MockMvc mvc;

    ModelInstanceConsoleScopeTest() {
        var controller = new KnowledgeModelConsoleProxyController(http, HttpClient.newHttpClient(), "http://knowledge", "http://model");
        controller.configureModelServiceSecret("model-auth-unit-test-secret");
        controller.setModelScope(catalog);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void projectManagerCannotEditArchiveOrMoveModelsOutsideTheProject() throws Exception {
        doReturn(ResponseEntity.ok(Map.of("data", instance("global", null)))).when(catalog).get(session, "global");
        doReturn(ResponseEntity.ok(Map.of("data", instance("m1", "orders")))).when(catalog).get(session, "m1");
        mvc.perform(post("/model/instances/global/archive")
                .requestAttr(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE, session)).andExpect(status().isForbidden());
        mvc.perform(put("/model/instances/m1").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\",\"projectCode\":\"finance\"}")
                .requestAttr(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE, session)).andExpect(status().isForbidden());

        mvc.perform(post("/model/instances/from-template/openai").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}")
                .requestAttr(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE, session)).andExpect(status().isForbidden());

        mvc.perform(put("/model/instances/provider-accounts/acc-1").contentType(MediaType.APPLICATION_JSON).content("{}")
                .requestAttr(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE, session)).andExpect(status().isForbidden());
        mvc.perform(post("/model/catalog/sync")
                .requestAttr(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE, session)).andExpect(status().isForbidden());
        // A draft naming another project's saved instance would send its stored key to the draft's address.
        mvc.perform(post("/model/instances/discover-models").contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":\"global\",\"connection\":{\"baseUrl\":\"https://elsewhere.example\",\"apiKey\":\"******\"}}")
                .requestAttr(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE, session)).andExpect(status().isForbidden());
        mvc.perform(post("/model/instances/test-draft").contentType(MediaType.APPLICATION_JSON).content("{\"id\":{\"x\":1}}")
                .requestAttr(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE, session)).andExpect(status().isBadRequest());
        model.expect(requestTo("http://model/model/instances/provider-accounts/discover")).andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body("{\"code\":200}"));
        model.expect(requestTo("http://model/model/instances/test-draft")).andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body("{\"code\":200}"));
        model.expect(requestTo("http://model/model/instances/m1/test")).andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body("{\"code\":200}"));
        mvc.perform(post("/model/instances/provider-accounts/discover").contentType(MediaType.APPLICATION_JSON).content("{}")
                .requestAttr(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE, session)).andExpect(status().isOk());
        mvc.perform(post("/model/instances/test-draft").contentType(MediaType.APPLICATION_JSON).content("{\"id\":\"m1\"}")
                .requestAttr(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE, session)).andExpect(status().isOk());
        mvc.perform(post("/model/instances/m1/test")
                .requestAttr(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE, session)).andExpect(status().isOk());
        model.verify();
    }

    @Test
    void connectionProbeRequiresSourceScopeAndCannotOverrideTheEndpoint() throws Exception {
        doReturn(ResponseEntity.ok(Map.of("data", instance("global", null)))).when(catalog).get(session, "global");
        doReturn(ResponseEntity.ok(Map.of("data", instance("m1", "orders")))).when(catalog).get(session, "m1");
        String valid = "{\"modelType\":\"LLM\",\"modelName\":\"qwen-plus\"}";
        mvc.perform(post("/model/instances/global/test-connection-model").contentType(MediaType.APPLICATION_JSON)
                .content(valid).requestAttr(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE, session))
                .andExpect(status().isForbidden());
        mvc.perform(post("/model/instances/m1/test-connection-model").contentType(MediaType.APPLICATION_JSON)
                .content("{\"modelType\":\"LLM\",\"modelName\":\"qwen-plus\",\"connection\":{\"baseUrl\":\"https://elsewhere.example\"}}")
                .requestAttr(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE, session))
                .andExpect(status().isBadRequest());
        String create = "{\"name\":\"Chat\",\"modelType\":\"LLM\",\"modelName\":\"qwen-plus\"}";
        mvc.perform(post("/model/instances/global/connection-models").contentType(MediaType.APPLICATION_JSON)
                .content(create).requestAttr(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE, session))
                .andExpect(status().isForbidden());
        mvc.perform(post("/model/instances/m1/connection-models").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Chat\",\"modelType\":\"LLM\",\"modelName\":\"qwen-plus\",\"projectCode\":\"finance\"}")
                .requestAttr(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE, session))
                .andExpect(status().isBadRequest());
        model.expect(requestTo("http://model/model/instances/m1/test-connection-model")).andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body("{\"code\":200}"));
        model.expect(requestTo("http://model/model/instances/m1/connection-models")).andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body("{\"code\":200}"));
        mvc.perform(post("/model/instances/m1/test-connection-model").contentType(MediaType.APPLICATION_JSON)
                .content(valid).requestAttr(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE, session))
                .andExpect(status().isOk());
        mvc.perform(post("/model/instances/m1/connection-models").contentType(MediaType.APPLICATION_JSON)
                .content(create).requestAttr(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE, session))
                .andExpect(status().isOk());
        model.verify();
    }

    private static Map<String, Object> instance(String id, String projectCode) {
        var model = new HashMap<String, Object>();
        model.put("id", id); model.put("projectCode", projectCode);
        return model;
    }
}
