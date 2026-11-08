package com.enterprise.ai.control.mcp.application.platform;

import com.enterprise.ai.control.identity.PlatformAiConnectionService.ConnectionIdentity;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformPrincipal;
import com.enterprise.ai.control.mcp.application.port.McpPlatformEmbedPort;
import com.enterprise.ai.control.mcp.application.port.McpPlatformModelCatalogPort;
import com.enterprise.ai.control.mcp.application.port.McpPlatformProjectPagesPort;
import com.enterprise.ai.control.mcp.application.port.McpPlatformPublicationPort;
import com.enterprise.ai.control.mcp.application.port.McpPlatformSystemStatusPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpPlatformIntegrationServiceTest {
    private final McpPlatformProjectPagesPort pages = mock(McpPlatformProjectPagesPort.class);
    private final McpPlatformEmbedPort embed = mock(McpPlatformEmbedPort.class);
    private final McpPlatformModelCatalogPort models = mock(McpPlatformModelCatalogPort.class);
    private final McpPlatformPublicationPort publications = mock(McpPlatformPublicationPort.class);
    private final McpPlatformSystemStatusPort status = mock(McpPlatformSystemStatusPort.class);
    private final McpPlatformIntegrationService service =
            new McpPlatformIntegrationService(pages, embed, models, publications, status, new ObjectMapper());
    private final PlatformAuthenticatedSession actor = new PlatformAuthenticatedSession(new PlatformPrincipal(7L, "dev", "Dev"),
            "s", LocalDateTime.now().plusHours(1), List.of(), List.of("platform:read"), List.of());
    private final ConnectionIdentity identity = new ConnectionIdentity(3L, "CODEX", actor);

    @AfterEach void clear() { RequestContextHolder.resetRequestAttributes(); }

    @Test
    void everyGuideTopicHasAFileAndGetsTheRealAddressAndProject() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp/platform");
        request.addHeader("X-Forwarded-Proto", "https");
        request.addHeader("X-Forwarded-Host", "reachai.example.com");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        for (String topic : McpPlatformIntegrationService.GUIDE_TOPICS.keySet()) {
            ResponseEntity<?> response = service.guide(topic, "orders");
            String markdown = (String) ((Map<?, ?>) response.getBody()).get("markdown");
            assertFalse(markdown.contains("{{"), topic + " still has a placeholder");
            assertTrue(markdown.contains("验证情况"), topic + " must state how it was verified");
            assertTrue(markdown.contains("https://reachai.example.com") || !topic.startsWith("embed"), topic);
        }
        assertEquals(400, service.guide("php", "orders").getStatusCode().value());
    }

    @Test
    void doctorKeepsGoingWhenOneCheckFailsAndSummarisesProblems() {
        doReturn(ResponseEntity.ok(Map.of("summary", Map.of("discoveredCount", 3, "completedCount", 1, "activeCount", 1),
                "runtimeAvailable", true))).when(pages).read(eq(actor), eq("orders"), eq(McpPlatformProjectPagesPort.Read.ACCESS_CENTER), anyMap());
        doReturn(ResponseEntity.ok(List.of(Map.of("status", "UP"), Map.of("status", "DOWN"))))
                .when(pages).read(eq(actor), eq("orders"), eq(McpPlatformProjectPagesPort.Read.INSTANCES), anyMap());
        when(embed.read(eq(actor), eq(McpPlatformEmbedPort.Read.CREDENTIAL_POLICIES), anyMap()))
                .thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN));
        doReturn(ResponseEntity.ok(Map.of("records", List.of()))).when(models)
                .list(eq(actor), eq("orders"), eq("LLM"), any(), eq(false), anyInt(), anyInt());
        doReturn(ResponseEntity.ok(List.of(Map.of("id", 1)))).when(publications).read(eq(actor), any(), any(), anyMap());
        doReturn(ResponseEntity.ok(Map.of("runtime", "UP", "model", "UP"))).when(status).read(eq(actor), any());

        Map<?, ?> body = (Map<?, ?>) service.doctor(identity, "orders").getBody();
        List<?> checks = (List<?>) body.get("checks");
        Map<String, String> byKey = new java.util.HashMap<>();
        for (Object item : checks) byKey.put((String) ((Map<?, ?>) item).get("key"), (String) ((Map<?, ?>) item).get("status"));
        assertEquals("PASS", byKey.get("PROJECT"));
        assertEquals("PASS", byKey.get("SDK_INSTANCES"));
        assertEquals("PASS", byKey.get("PAGES"));
        assertEquals("WARN", byKey.get("EMBED_POLICY"));
        assertEquals("FAIL", byKey.get("MODEL"));
        assertEquals("PASS", byKey.get("EMPLOYEE_MCP"));
        assertEquals("PASS", byKey.get("SERVICES"));
        assertEquals("1 项有问题，1 项需要注意", body.get("summary"));
        assertFalse(((List<?>) body.get("localChecks")).isEmpty());
    }
}
