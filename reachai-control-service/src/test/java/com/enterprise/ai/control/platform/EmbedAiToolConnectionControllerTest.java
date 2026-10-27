package com.enterprise.ai.control.platform;

import com.enterprise.ai.control.agentskill.AgentSkillEmployeeCatalog;
import com.enterprise.ai.control.agentskill.AgentSkillException;
import com.enterprise.ai.control.identity.BusinessUserSyncService;
import com.enterprise.ai.control.identity.BusinessUserSyncService.AttestedBusinessUser;
import com.enterprise.ai.control.identity.BusinessUserSyncService.BusinessUserDisabledException;
import com.enterprise.ai.control.identity.BusinessUserSyncService.SyncedBusinessUser;
import com.enterprise.ai.control.mcp.application.employee.AiToolConnectionService;
import com.enterprise.ai.control.mcp.application.employee.AiToolConnectionService.ConnectionView;
import com.enterprise.ai.control.mcp.application.employee.AiToolConnectionService.CreatedConnection;
import com.enterprise.ai.control.mcp.application.employee.AiToolConnectionService.EmployeeOwner;
import com.enterprise.ai.control.mcp.application.employee.AiToolConnectionService.SystemOption;
import com.enterprise.ai.control.mcp.infrastructure.McpHubProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EmbedAiToolConnectionControllerTest {

    private final PlatformEmbedTokenService tokenService = mock(PlatformEmbedTokenService.class);
    private final BusinessUserSyncService sync = mock(BusinessUserSyncService.class);
    private final AiToolConnectionService connections = mock(AiToolConnectionService.class);
    private final McpHubProperties properties = new McpHubProperties();
    private final AgentSkillEmployeeCatalog skills = mock(AgentSkillEmployeeCatalog.class);
    private final EmployeePortalSessionService portalSessions = new EmployeePortalSessionService();
    private final EmbedAiToolConnectionController controller =
            new EmbedAiToolConnectionController(tokenService, sync, connections,
                    mock(com.enterprise.ai.control.mcp.application.employee.McpWriteApprovalService.class), properties,
                    skills, portalSessions);

    @Test
    void skillsAreLimitedToTheSystemsTheEmployeeCanConnect() {
        when(tokenService.verify("tok")).thenReturn(claims());
        when(sync.sync(any())).thenReturn(synced());
        when(connections.options(any())).thenReturn(List.of(new SystemOption("yudao-erp", "芋道 ERP", 5, true)));
        when(skills.list(Map.of("yudao-erp", "芋道 ERP"))).thenReturn(List.of());

        assertEquals(HttpStatus.OK, controller.skills("Bearer tok").getStatusCode());
        verify(skills).list(Map.of("yudao-erp", "芋道 ERP"));
    }

    @Test
    void skillPackageDownloadsZipAndHidesOtherSkills() {
        when(tokenService.verify("tok")).thenReturn(claims());
        when(sync.sync(any())).thenReturn(synced());
        when(connections.options(any())).thenReturn(List.of(new SystemOption("yudao-erp", "芋道 ERP", 5, true)));
        when(skills.packageFor(eq(4L), any())).thenReturn(
                new AgentSkillEmployeeCatalog.Package("yudao-erp-create-customer-1.0.0.zip", "abc", new byte[]{1, 2}));
        when(skills.packageFor(eq(5L), any())).thenThrow(AgentSkillException.notFound("hidden"));

        var ok = controller.skillPackage("Bearer tok", 4L);
        assertEquals(HttpStatus.OK, ok.getStatusCode());
        assertTrue(ok.getHeaders().getFirst("Content-Disposition").contains("yudao-erp-create-customer-1.0.0.zip"));
        assertEquals(HttpStatus.NOT_FOUND, controller.skillPackage("Bearer tok", 5L).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, controller.skillPackage(null, 4L).getStatusCode());
    }

    @Test
    void employeeComesFromTheVerifiedEmbedTokenAndIsSynced() {
        when(tokenService.verify("tok")).thenReturn(claims());
        when(sync.sync(any())).thenReturn(synced());
        when(connections.list(any())).thenReturn(List.of());

        var response = controller.list("Bearer tok");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(sync).sync(new AttestedBusinessUser("Tenant-A", "yudao-erp", "1", null, "张三", List.of("erp_user")));
        verify(connections).list(new EmployeeOwner("tenant-a", 7L, "yudao-erp", "yudao-erp", "1", "张三",
                List.of("erp_user")));
    }

    @Test
    void missingOrInvalidTokenIsUnauthorizedAndSyncsNobody() {
        when(tokenService.verify("bad")).thenThrow(new PlatformEmbedTokenException("invalid"));

        assertEquals(HttpStatus.UNAUTHORIZED, controller.options(null).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, controller.options("Bearer bad").getStatusCode());
        verifyNoInteractions(sync, connections);
    }

    @Test
    void disabledEmployeeIsForbidden() {
        when(tokenService.verify("tok")).thenReturn(claims());
        when(sync.sync(any())).thenThrow(new BusinessUserDisabledException(7L));

        assertEquals(HttpStatus.FORBIDDEN, controller.options("Bearer tok").getStatusCode());
        verifyNoInteractions(connections);
    }

    @Test
    void createReturnsTheOneLineSetupForTheChosenTool() {
        properties.setPublicBaseUrl("https://reachai.example.com/");
        when(tokenService.verify("tok")).thenReturn(claims());
        when(sync.sync(any())).thenReturn(synced());
        ConnectionView view = new ConnectionView(3L, "CLAUDE_CODE", "rai_abcdef12", "ACTIVE", List.of(),
                LocalDateTime.now(), null, LocalDateTime.now().plusDays(90));
        when(connections.create(any(), eq("CLAUDE_CODE"), eq(List.of("yudao-erp"))))
                .thenReturn(new CreatedConnection(view, "rai_secret"));

        var response = controller.create("Bearer tok",
                new EmbedAiToolConnectionController.CreateConnectionRequest("CLAUDE_CODE", List.of("yudao-erp")),
                new MockHttpServletRequest());

        var setup = response.getBody().getData().setup();
        assertEquals("https://reachai.example.com/mcp/employee", setup.endpointUrl());
        assertEquals("COMMAND", setup.kind());
        assertTrue(setup.content().startsWith("claude mcp add --transport http reachai https://reachai.example.com/mcp/employee"));
        assertTrue(setup.content().contains("Authorization: Bearer rai_secret"));
    }

    @Test
    void portalTicketLinkOpensThePersonalPageForTheSameEmployee() {
        properties.setPublicBaseUrl("https://reachai.example.com/");
        when(tokenService.verify("tok")).thenReturn(claims());
        when(sync.sync(any())).thenReturn(synced());

        var url = controller.portalTicket("Bearer tok", new MockHttpServletRequest()).getBody().getData().url();

        assertTrue(url.startsWith("https://reachai.example.com/my/ai-tools#ticket="));
        String ticket = url.substring(url.indexOf("#ticket=") + "#ticket=".length());
        var session = portalSessions.redeem(ticket).orElseThrow();
        assertEquals(new EmployeeOwner("tenant-a", 7L, "yudao-erp", "yudao-erp", "1", "张三",
                List.of("erp_user")), session.owner());
    }

    private PlatformEmbedTokenClaims claims() {
        PlatformEmbedTokenClaims claims = new PlatformEmbedTokenClaims();
        claims.setTenantId("Tenant-A");
        claims.setAppId("yudao-erp");
        claims.setProjectCode("yudao-erp");
        claims.setExternalUserId("1");
        claims.setUserName("张三");
        claims.setRoles(List.of("erp_user"));
        return claims;
    }

    private SyncedBusinessUser synced() {
        return new SyncedBusinessUser(7L, "tenant-a", "yudao-erp:1", "张三", "yudao-erp", "1", "张三",
                List.of("erp_user"));
    }
}
