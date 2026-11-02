package com.enterprise.ai.control.compat;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformConsoleAuthInterceptor;
import com.enterprise.ai.control.identity.PlatformPrincipal;
import com.enterprise.ai.control.mcp.application.port.McpPlatformKnowledgeCatalogPort;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class KnowledgeChunkConsoleScopeTest {
    private final RestTemplate http = new RestTemplate();
    private final MockRestServiceServer knowledge = MockRestServiceServer.createServer(http);
    private final McpPlatformKnowledgeCatalogPort catalog = mock(McpPlatformKnowledgeCatalogPort.class);
    private final PlatformAuthenticatedSession session = new PlatformAuthenticatedSession(new PlatformPrincipal(7L, "builder", "Builder"),
            "console", LocalDateTime.now().plusHours(1), List.of(), List.of("knowledge:write"), List.of());
    private final MockMvc mvc;

    KnowledgeChunkConsoleScopeTest() {
        var controller = new KnowledgeModelConsoleProxyController(http, HttpClient.newHttpClient(), "http://knowledge", "http://model");
        controller.setChunkScope(catalog);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void chunkWritesNeedTheFileAndKnowledgeWriteOnItsKnowledgeBase() throws Exception {
        mvc.perform(put("/ai/knowledge/chunks/7").contentType(MediaType.APPLICATION_JSON).content("{}")
                .requestAttr(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE, session)).andExpect(status().isBadRequest());
        doReturn(ResponseEntity.status(403).build()).when(catalog).fileChunksForWrite(session, "other");
        mvc.perform(post("/ai/knowledge/chunks/7/toggle").param("fileId", "other").param("enabled", "0")
                .requestAttr(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE, session)).andExpect(status().isForbidden());
        var chunks = new ObjectMapper().readTree("[{\"id\":7}]");
        doReturn(ResponseEntity.ok(Map.of("resource", Map.of(), "chunks", chunks))).when(catalog).fileChunksForWrite(session, "f1");
        mvc.perform(post("/ai/knowledge/chunks/8/reembed").param("fileId", "f1")
                .requestAttr(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE, session)).andExpect(status().isForbidden());

        knowledge.expect(requestTo("http://knowledge/ai/knowledge/chunks/7/reembed")).andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body("{\"code\":200}"));
        mvc.perform(post("/ai/knowledge/chunks/7/reembed").param("fileId", "f1")
                .requestAttr(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE, session)).andExpect(status().isOk());
        knowledge.verify();
    }
}
