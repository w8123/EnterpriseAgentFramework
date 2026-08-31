package com.enterprise.ai.control.mcp.api.protocol;

import com.enterprise.ai.control.mcp.application.protocol.McpProtocolApplicationService;
import com.enterprise.ai.control.mcp.infrastructure.McpHubProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class McpRequestBodyLimitFilterTest {

    @Test
    void rejectsChunkedBodyThatExceedsTheConfiguredLimitBeforeJackson() throws Exception {
        McpHubProperties properties = properties(8);
        McpProtocolApplicationService service = mock(McpProtocolApplicationService.class);
        McpRequestBodyLimitFilter filter = new McpRequestBodyLimitFilter(
                properties, new McpProtocolRequestGuard(properties), service);
        MockHttpServletRequest request = chunkedRequest("123456789");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilterInternal(request, response, chain);

        assertEquals(413, response.getStatus());
        assertTrue(response.getContentAsString().contains("MCP request body is too large"));
        assertNull(chain.getRequest());
        ArgumentCaptor<McpProtocolApplicationService.McpAuditRequest> audit =
                ArgumentCaptor.forClass(McpProtocolApplicationService.McpAuditRequest.class);
        verify(service).audit(audit.capture());
        assertEquals("MCP_REQUEST_TOO_LARGE", audit.getValue().errorMessage());
    }

    @Test
    void disabledEndpointReturnsNotFoundWithoutTouchingPersistence() throws Exception {
        McpHubProperties properties = properties(8);
        properties.setEnabled(false);
        McpProtocolApplicationService service = mock(McpProtocolApplicationService.class);
        McpRequestBodyLimitFilter filter = new McpRequestBodyLimitFilter(
                properties, new McpProtocolRequestGuard(properties), service);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(chunkedRequest("{}"), response, new MockFilterChain());

        assertEquals(404, response.getStatus());
        verify(service, never()).audit(org.mockito.ArgumentMatchers.any());
    }

    private McpHubProperties properties(int maximum) {
        McpHubProperties properties = new McpHubProperties();
        properties.setEnabled(true);
        properties.setMaxRequestBytes(maximum);
        return properties;
    }

    private MockHttpServletRequest chunkedRequest(String body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp") {
            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        request.setServletPath("/mcp");
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        request.setRemoteAddr("127.0.0.1");
        return request;
    }
}
