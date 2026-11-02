package com.enterprise.ai.control.compat;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RegistryInstanceConsoleOperationsTest {
    @Test
    @SuppressWarnings("unchecked")
    void theBridgeSeesTheConsoleInstancePathAndJsonBody() {
        var proxy = mock(CapabilityCompatibilityProxyController.class);
        var operations = new RegistryInstanceConsoleOperations(mock(RegistryCompatibilityController.class), proxy, new ObjectMapper());
        when(proxy.proxyRegistry(any(), any())).thenReturn(ResponseEntity.ok("{\"removed\":2}".getBytes(StandardCharsets.UTF_8)));

        var response = operations.change(new MockHttpServletRequest("POST", "/mcp/platform"), "orders", "purge-offline", Map.of("minIdleMinutes", 30));

        ArgumentCaptor<RequestEntity<byte[]>> entity = ArgumentCaptor.forClass(RequestEntity.class);
        ArgumentCaptor<HttpServletRequest> request = ArgumentCaptor.forClass(HttpServletRequest.class);
        verify(proxy).proxyRegistry(entity.capture(), request.capture());
        assertEquals("/api/registry/projects/orders/instances/purge-offline", request.getValue().getRequestURI());
        assertEquals("{\"minIdleMinutes\":30}", new String(entity.getValue().getBody(), StandardCharsets.UTF_8));
        assertEquals(2, ((com.fasterxml.jackson.databind.JsonNode) response.getBody()).path("removed").asInt());
    }
}
