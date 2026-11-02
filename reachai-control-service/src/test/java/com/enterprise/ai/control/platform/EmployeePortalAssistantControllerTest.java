package com.enterprise.ai.control.platform;

import com.enterprise.ai.control.mcp.application.employee.AiToolConnectionService.EmployeeOwner;
import com.enterprise.ai.control.mcp.infrastructure.McpHubProperties;
import com.enterprise.ai.control.platform.EmployeePortalAssistantController.AssistantTokenRequest;
import com.enterprise.ai.control.platform.EmployeePortalSessionService.ChatGrant;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Map;
import java.time.Clock;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentMatchers.anyString;

class EmployeePortalAssistantControllerTest {

    private final EmployeePortalSessionService sessions = new EmployeePortalSessionService();
    private final PlatformEmbedTokenProperties tokenProperties = tokenProperties();
    private final PlatformEmbedTokenService tokenService = new PlatformEmbedTokenService(new ObjectMapper(), tokenProperties);
    private final McpHubProperties hubProperties = new McpHubProperties();
    private final EmployeePortalAssistantController controller = new EmployeePortalAssistantController(
            sessions, tokenService, tokenProperties, hubProperties);

    @Test
    void mintsAnEmbedTokenForTheSameAgentAndAttestedUser() {
        hubProperties.setPublicBaseUrl("https://reachai.example.com");
        String portal = openPortal(new ChatGrant("default", "yudao-erp", "yudao-erp", "agent-erp",
                "1", "g-1", "张三", List.of("erp_user"), Map.of("dept", "sales")));

        var response = controller.token("Bearer " + portal, new AssistantTokenRequest("page-abc"),
                new MockHttpServletRequest());

        assertEquals(HttpStatus.OK, response.getStatusCode());
        var body = response.getBody().getData();
        assertEquals("agent-erp", body.agentId());
        assertTrue(body.expiresIn() > 0 && body.expiresIn() <= 600);
        PlatformEmbedTokenClaims claims = tokenService.verify(body.token());
        assertEquals("yudao-erp", claims.getProjectCode());
        assertEquals("agent-erp", claims.getAgentId());
        assertEquals("1", claims.getExternalUserId());
        assertEquals("g-1", claims.getGlobalUserId());
        assertEquals(List.of("erp_user"), claims.getRoles());
        assertEquals("sales", claims.getAttributes().get("dept"));
        assertEquals("page-abc", claims.getPageInstanceId());
        assertEquals("/my/ai-tools", claims.getRoute());
        assertEquals("https://reachai.example.com", claims.getOrigin());
    }

    @Test
    void refusesWithoutAPortalSessionOrChatGrant() {
        var noSession = controller.token("Bearer nope", new AssistantTokenRequest("page-abc"),
                new MockHttpServletRequest());
        assertEquals(HttpStatus.UNAUTHORIZED, noSession.getStatusCode());

        String withoutChat = openPortal(null);
        var noGrant = controller.token("Bearer " + withoutChat, new AssistantTokenRequest("page-abc"),
                new MockHttpServletRequest());
        assertEquals(HttpStatus.CONFLICT, noGrant.getStatusCode());
    }

    @Test
    void rejectsMalformedPageInstanceIds() {
        String portal = openPortal(new ChatGrant("default", "yudao-erp", "yudao-erp", "agent-erp",
                "1", null, null, null, null));

        assertEquals(HttpStatus.BAD_REQUEST, controller.token("Bearer " + portal,
                new AssistantTokenRequest("bad id with spaces"), new MockHttpServletRequest()).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, controller.token("Bearer " + portal, null,
                new MockHttpServletRequest()).getStatusCode());
    }

    @Test
    void assistantTokenRetainsSourceBusinessSessionAndExpiresWithIt() {
        var revocations = mock(PlatformEmbedBusinessSessionRevocation.class);
        var boundSessions = new EmployeePortalSessionService(Clock.systemUTC(), revocations);
        var boundTokenService = new PlatformEmbedTokenService(new ObjectMapper(), tokenProperties, Clock.systemUTC(), revocations);
        var boundController = new EmployeePortalAssistantController(boundSessions, boundTokenService, tokenProperties, hubProperties);
        Instant expires = Instant.now().plusSeconds(120);
        var grant = new ChatGrant("default", "yudao-erp", "yudao-erp", "agent-erp", "1", null,
                null, List.of(), Map.of(), "a".repeat(32), expires);
        var owner = new EmployeeOwner("default", 7L, "yudao-erp", "yudao-erp", "1", "name", List.of());
        String portal = boundSessions.redeem(boundSessions.issueTicket(owner, grant).value()).orElseThrow().value();
        var response = boundController.token("Bearer " + portal, new AssistantTokenRequest("page-abc"),
                new MockHttpServletRequest());
        assertEquals(HttpStatus.OK, response.getStatusCode());
        String token = response.getBody().getData().token();
        var claims = boundTokenService.verify(token);
        assertEquals(grant.businessSessionId(), claims.getBusinessSessionId());
        assertTrue(claims.getExpiresAt() <= expires.getEpochSecond());
        doThrow(new PlatformEmbedTokenException("revoked")).when(revocations).requireActive(anyString(), anyString());
        assertThrows(PlatformEmbedTokenException.class, () -> boundTokenService.verify(token));
        assertTrue(boundSessions.resolve(portal).isEmpty());
    }

    private String openPortal(ChatGrant chat) {
        EmployeeOwner owner = new EmployeeOwner("default", 7L, "yudao-erp", "yudao-erp", "1", "张三",
                List.of("erp_user"));
        String ticket = sessions.issueTicket(owner, chat).value();
        return sessions.redeem(ticket).orElseThrow().value();
    }

    private static PlatformEmbedTokenProperties tokenProperties() {
        PlatformEmbedTokenProperties properties = new PlatformEmbedTokenProperties();
        properties.setSecret("test-secret-for-portal-assistant-0123456789");
        return properties;
    }
}
