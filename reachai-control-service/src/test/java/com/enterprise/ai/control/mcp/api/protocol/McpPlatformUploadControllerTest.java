package com.enterprise.ai.control.mcp.api.protocol;

import com.enterprise.ai.control.identity.PlatformAiConnectionService;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformPrincipal;
import com.enterprise.ai.control.mcp.application.platform.McpPlatformProtocolService;
import com.enterprise.ai.control.mcp.application.platform.McpPlatformUploadStore;
import com.enterprise.ai.control.mcp.domain.McpDomainException;
import com.enterprise.ai.control.mcp.infrastructure.McpHubProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class McpPlatformUploadControllerTest {
    private final McpPlatformProtocolService platform = mock(McpPlatformProtocolService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new McpPlatformUploadController(
            new McpProtocolRequestGuard(new McpHubProperties()), platform, new McpPlatformUploadStore())).build();

    @Test
    void uploadNeedsAValidPlatformConnectionToken() throws Exception {
        mvc.perform(post("/mcp/platform/uploads").param("fileName", "a.pdf").contentType(MediaType.APPLICATION_OCTET_STREAM).content(new byte[] {1}))
                .andExpect(status().isUnauthorized());
        when(platform.authenticate("bad")).thenThrow(new McpDomainException("MCP_CLIENT_UNAUTHORIZED", "invalid"));
        mvc.perform(post("/mcp/platform/uploads").param("fileName", "a.pdf").header("Authorization", "Bearer bad")
                .contentType(MediaType.APPLICATION_OCTET_STREAM).content(new byte[] {1})).andExpect(status().isUnauthorized());
        when(platform.authenticate("good")).thenReturn(new PlatformAiConnectionService.ConnectionIdentity(19L, "CODEX",
                new PlatformAuthenticatedSession(new PlatformPrincipal(42L, "operator", "操作员"), "s", null, List.of(), List.of(), List.of())));
        mvc.perform(post("/mcp/platform/uploads").param("fileName", "a.pdf").header("Authorization", "Bearer good")
                .contentType(MediaType.APPLICATION_OCTET_STREAM).content(new byte[] {1, 2}))
                .andExpect(status().isOk()).andExpect(jsonPath("$.size").value(2)).andExpect(jsonPath("$.fileName").value("a.pdf"));
    }
}
