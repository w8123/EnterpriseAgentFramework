package com.enterprise.ai.control.compat;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformConsoleAuthInterceptor;
import com.enterprise.ai.control.identity.PlatformPermissionGrant;
import com.enterprise.ai.control.identity.PlatformPrincipal;
import com.enterprise.ai.control.mcp.application.port.McpPlatformKnowledgeCatalogPort;
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
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class KnowledgeBaseConsoleScopeTest {
    private final RestTemplate http = new RestTemplate();
    private final MockRestServiceServer knowledge = MockRestServiceServer.createServer(http);
    private final McpPlatformKnowledgeCatalogPort catalog = mock(McpPlatformKnowledgeCatalogPort.class);
    // Knowledge write only inside project "orders".
    private final PlatformAuthenticatedSession session = new PlatformAuthenticatedSession(new PlatformPrincipal(7L, "builder", "Builder"),
            "console", LocalDateTime.now().plusHours(1), List.of(), List.of("knowledge:read", "knowledge:write"),
            List.of(new PlatformPermissionGrant("knowledge:read", "PROJECT", "orders"), new PlatformPermissionGrant("knowledge:write", "PROJECT", "orders")));
    private final MockMvc mvc;

    KnowledgeBaseConsoleScopeTest() {
        var controller = new KnowledgeModelConsoleProxyController(http, HttpClient.newHttpClient(), "http://knowledge", "http://model");
        controller.setChunkScope(catalog);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void createLandsOnlyInAScopeTheCallerCanWrite() throws Exception {
        mvc.perform(post("/ai/knowledge/base").contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"kb\",\"name\":\"KB\",\"scope\":\"PROJECT\",\"projectCode\":\"finance\"}")
                .requestAttr(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE, session)).andExpect(status().isForbidden());
        mvc.perform(post("/ai/knowledge/base").contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"kb\",\"name\":\"KB\"}")
                .requestAttr(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE, session)).andExpect(status().isForbidden());

        knowledge.expect(requestTo("http://knowledge/ai/knowledge/base")).andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body("{\"code\":200}"));
        mvc.perform(post("/ai/knowledge/base").contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"kb\",\"name\":\"KB\",\"scope\":\"PROJECT\",\"projectCode\":\"orders\"}")
                .requestAttr(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE, session)).andExpect(status().isOk());
        knowledge.verify();
    }

    @Test
    void deletesNeedWriteOnTheNamedBaseOrFile() throws Exception {
        doReturn(ResponseEntity.status(403).build()).when(catalog).baseForWrite(session, "finance-kb");
        mvc.perform(delete("/ai/knowledge/base/finance-kb")
                .requestAttr(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE, session)).andExpect(status().isForbidden());
        doReturn(ResponseEntity.status(403).build()).when(catalog).fileChunksForWrite(session, "f9");
        mvc.perform(delete("/ai/file/f9")
                .requestAttr(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE, session)).andExpect(status().isForbidden());
        // Owner endpoints this console never calls stay closed.
        mvc.perform(delete("/ai/knowledge/file").param("knowledgeBaseCode", "orders-kb").param("fileId", "f1")
                .requestAttr(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE, session)).andExpect(status().isForbidden());

        doReturn(ResponseEntity.ok(Map.of("scope", "PROJECT"))).when(catalog).baseForWrite(session, "orders-kb");
        knowledge.expect(requestTo("http://knowledge/ai/knowledge/base/orders-kb")).andExpect(method(HttpMethod.DELETE))
                .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body("{\"code\":200}"));
        mvc.perform(delete("/ai/knowledge/base/orders-kb")
                .requestAttr(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE, session)).andExpect(status().isOk());
        knowledge.verify();
    }

    @Test
    void retrievalTestReadsOnlyBasesTheCallerCanRead() throws Exception {
        mvc.perform(post("/ai/retrieval/test").contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"q\"}")
                .requestAttr(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE, session)).andExpect(status().isBadRequest());
        mvc.perform(post("/ai/retrieval/test").contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"q\",\"knowledgeBaseCodes\":[\"orders-kb\"],\"accessSnapshot\":{}}")
                .requestAttr(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE, session)).andExpect(status().isBadRequest());
        doReturn(ResponseEntity.ok(Map.of())).when(catalog).read(session, McpPlatformKnowledgeCatalogPort.Operation.STATS, Map.of("kbCode", "orders-kb"));
        doReturn(ResponseEntity.status(403).build()).when(catalog).read(session, McpPlatformKnowledgeCatalogPort.Operation.STATS, Map.of("kbCode", "finance-kb"));
        mvc.perform(post("/ai/retrieval/test").contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"q\",\"knowledgeBaseCodes\":[\"orders-kb\",\"finance-kb\"]}")
                .requestAttr(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE, session)).andExpect(status().isForbidden());

        knowledge.expect(requestTo("http://knowledge/ai/retrieval/test")).andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body("{\"code\":200}"));
        mvc.perform(post("/ai/retrieval/test").contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"q\",\"knowledgeBaseCodes\":[\"orders-kb\"],\"recordHit\":false}")
                .requestAttr(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE, session)).andExpect(status().isOk());
        knowledge.verify();
        verify(catalog, never()).baseForWrite(any(), any());
    }
}
