package com.enterprise.ai.control.mcp.api.protocol;

import com.enterprise.ai.control.mcp.application.protocol.McpProtocolApplicationService;
import com.enterprise.ai.control.mcp.infrastructure.McpHubProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class McpProtocolExceptionHandlerTest {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        McpHubProperties properties = new McpHubProperties();
        properties.setEnabled(true);
        McpProtocolApplicationService service = mock(McpProtocolApplicationService.class);
        ObjectMapper objectMapper = new ObjectMapper();
        McpEndpointController controller = new McpEndpointController(
                new McpProtocolRequestGuard(properties), service, properties, objectMapper,
                new McpAuditPayloadSanitizer(objectMapper, properties));
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new McpProtocolExceptionHandler(service))
                .build();
    }

    @Test
    void malformedJsonUsesJsonRpcParseError() throws Exception {
        mvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(-32700))
                .andExpect(jsonPath("$.error.message").value("Invalid MCP JSON payload"));
    }

    @Test
    void validJsonWithWrongTopLevelShapeUsesInvalidRequest() throws Exception {
        mvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(-32600))
                .andExpect(jsonPath("$.error.message").value("Invalid MCP protocol request"));
    }
}
