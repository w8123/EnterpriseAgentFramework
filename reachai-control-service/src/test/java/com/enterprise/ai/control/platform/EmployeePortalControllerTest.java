package com.enterprise.ai.control.platform;

import com.enterprise.ai.control.mcp.application.calllog.McpCallLogReader;
import com.enterprise.ai.control.mcp.application.employee.AiToolConnectionService;
import com.enterprise.ai.control.mcp.application.employee.AiToolConnectionService.ConnectedSystemView;
import com.enterprise.ai.control.mcp.application.employee.AiToolConnectionService.ConnectionView;
import com.enterprise.ai.control.mcp.application.employee.AiToolConnectionService.EmployeeOwner;
import com.enterprise.ai.control.mcp.application.employee.AiToolConnectionService.SystemOption;
import com.enterprise.ai.control.mcp.application.employee.McpWriteApprovalService;
import com.enterprise.ai.control.mcp.infrastructure.McpHubProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EmployeePortalControllerTest {

    private static final EmployeeOwner OWNER =
            new EmployeeOwner("t", 7L, "yudao-erp", "yudao-erp", "1", "张三", List.of("erp_user"));

    private final EmployeePortalSessionService sessions = new EmployeePortalSessionService();
    private final AiToolConnectionService connections = mock(AiToolConnectionService.class);
    private final McpCallLogReader callLogs = mock(McpCallLogReader.class);
    private final EmployeePortalController controller = new EmployeePortalController(sessions, connections,
            mock(McpWriteApprovalService.class), callLogs, new McpHubProperties(),
            mock(com.enterprise.ai.control.agentskill.AgentSkillEmployeeCatalog.class));

    @Test
    void ticketFromTheChatPanelSignsInWithoutAPassword() {
        when(connections.options(OWNER)).thenReturn(List.of(new SystemOption("yudao-erp", "芋道 ERP", 12, true)));
        String ticket = sessions.issueTicket(OWNER).value();

        var response = controller.signIn(new EmployeePortalController.SignInRequest(ticket));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        var body = response.getBody().getData();
        assertEquals("张三", body.me().userName());
        assertEquals("芋道 ERP", body.me().systemName());
        assertEquals(HttpStatus.OK, controller.profile("Bearer " + body.token()).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED,
                controller.signIn(new EmployeePortalController.SignInRequest(ticket)).getStatusCode());
    }

    @Test
    void everyPortalCallNeedsASession() {
        assertEquals(HttpStatus.UNAUTHORIZED, controller.list(null).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, controller.options("Bearer nope").getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, controller.calls("Bearer nope").getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, controller.signIn(null).getStatusCode());
        verifyNoInteractions(connections, callLogs);
    }

    @Test
    void callsOnlyCoverTheEmployeesOwnConnections() {
        String token = sessions.redeem(sessions.issueTicket(OWNER).value()).orElseThrow().value();
        when(connections.list(OWNER)).thenReturn(List.of(new ConnectionView(3L, "CLAUDE_CODE", "rai_abc", "ACTIVE",
                List.of(new ConnectedSystemView("yudao-erp", "芋道 ERP")), LocalDateTime.now(), null, null)));
        McpCallLogReader.Entry entry = new McpCallLogReader.Entry(41L, "INBOUND", 1L, null, "张三", "tools/call",
                "yudao_erp_customer_create", 1L, "yudao-erp", null, "t", false, 120L, "APPROVAL_PENDING",
                null, null, null, null, null, null, LocalDateTime.now(), 3L, "1", "claude-code");
        when(callLogs.recentByConnections(anyList(), anyInt())).thenReturn(List.of(entry));

        var calls = controller.calls("Bearer " + token).getBody().getData();

        verify(callLogs).recentByConnections(List.of(3L), EmployeePortalController.RECENT_CALL_LIMIT);
        assertEquals(1, calls.size());
        assertEquals("CLAUDE_CODE", calls.get(0).toolKind());
        assertEquals("芋道 ERP", calls.get(0).businessSystem());
        assertFalse(calls.get(0).success());
    }

    @Test
    void signOutEndsTheSession() {
        String token = sessions.redeem(sessions.issueTicket(OWNER).value()).orElseThrow().value();

        controller.signOut("Bearer " + token);

        assertEquals(HttpStatus.UNAUTHORIZED, controller.list("Bearer " + token).getStatusCode());
        assertTrue(sessions.resolve(token).isEmpty());
        assertTrue(controller.signOut(null).getBody().getData());
    }
}
