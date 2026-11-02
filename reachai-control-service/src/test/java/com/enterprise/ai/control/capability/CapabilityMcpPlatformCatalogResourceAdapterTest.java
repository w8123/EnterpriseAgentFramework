package com.enterprise.ai.control.capability;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformDelegatedRequestScope;
import com.enterprise.ai.control.identity.PlatformPrincipal;
import com.enterprise.ai.control.mcp.application.port.McpPlatformCatalogResourcePort.Area;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.RequestEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class CapabilityMcpPlatformCatalogResourceAdapterTest {
    @AfterEach void clearRequest() { RequestContextHolder.resetRequestAttributes(); }

    @Test
    @SuppressWarnings("unchecked")
    void theFacadeSeesTheConsoleAreaAndMethodNotTheMcpRequest() {
        var controller = mock(CatalogResourcesConsoleController.class);
        var adapter = new CapabilityMcpPlatformCatalogResourceAdapter(controller, new PlatformDelegatedRequestScope());
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest("POST", "/mcp/platform")));
        var actor = new PlatformAuthenticatedSession(new PlatformPrincipal(7L, "builder", "Builder"), "s", LocalDateTime.now().plusHours(1),
                List.of(), List.of(), List.of());

        adapter.call(actor, Area.API_GRAPH, "PUT", 3L, "/layout", Map.of(), Map.of("positions", List.of()));

        ArgumentCaptor<HttpServletRequest> request = ArgumentCaptor.forClass(HttpServletRequest.class);
        ArgumentCaptor<RequestEntity<Object>> entity = ArgumentCaptor.forClass(RequestEntity.class);
        verify(controller).resource(request.capture(), entity.capture(), eq(3L), eq("/layout"), any());
        assertEquals("/api/api-graph/projects/3/layout", request.getValue().getRequestURI());
        assertEquals("PUT", request.getValue().getMethod());
        assertEquals(Map.of("positions", List.of()), entity.getValue().getBody());
    }
}
