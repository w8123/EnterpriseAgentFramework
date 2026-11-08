package com.enterprise.ai.control.aicoding.api;

import com.enterprise.ai.control.aicoding.application.AiCodingConnectionClaimService;
import com.enterprise.ai.control.aicoding.application.AiCodingHandoffApplicationService;
import com.enterprise.ai.control.aicoding.application.AiCodingManagedExecutionService;
import com.enterprise.ai.control.aicoding.application.AiCodingManagedExecutionStreamService;
import com.enterprise.ai.control.aicoding.application.AiCodingTaskApplicationService;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.CreateTaskCommand;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.EventCommand;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskDetailView;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskView;
import com.enterprise.ai.control.aicoding.security.AiCodingTaskTokenGuard;
import com.enterprise.ai.control.identity.PlatformAiConnectionService.ConnectionIdentity;
import com.enterprise.ai.control.mcp.application.port.McpPlatformAiCodingPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformDelegatedRequestScope;
import com.enterprise.ai.control.identity.PlatformPermissionGrant;
import com.enterprise.ai.control.identity.PlatformPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AiCodingTaskMcpPlatformAdapterTest {
    private final AiCodingTaskApplicationService tasks = mock(AiCodingTaskApplicationService.class);
    private final AiCodingConnectionClaimService claims = mock(AiCodingConnectionClaimService.class);
    private final AiCodingTaskTokenGuard guard = mock(AiCodingTaskTokenGuard.class);
    private final AiCodingTaskMcpPlatformAdapter adapter = new AiCodingTaskMcpPlatformAdapter(new AiCodingTaskConsoleController(tasks,
            mock(AiCodingHandoffApplicationService.class), mock(AiCodingManagedExecutionService.class), mock(AiCodingManagedExecutionStreamService.class)),
            new PlatformDelegatedRequestScope(), new ObjectMapper(), claims, tasks, guard);
    private final PlatformAuthenticatedSession actor = new PlatformAuthenticatedSession(new PlatformPrincipal(7L, "builder", "Builder"),
            "s", LocalDateTime.now().plusHours(1), List.of(), List.of("platform:write"),
            List.of(new PlatformPermissionGrant("platform:write", "PROJECT", "orders")));

    @AfterEach void clearRequest() { RequestContextHolder.resetRequestAttributes(); }

    @Test
    void createIsProjectScopedAndRecordsTheAccountAsCreator() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest("POST", "/mcp/platform")));
        Map<String, Object> command = Map.of("projectCode", "finance", "taskKind", "PAGE_MAP", "executorProvider", "CODEX",
                "title", "t", "objective", "o", "targets", List.of(Map.of("targetType", "PROJECT", "targetKey", "finance")));
        assertThrows(ResponseStatusException.class, () -> adapter.create(actor, command));
        verifyNoInteractions(tasks);

        adapter.create(actor, Map.of("projectCode", "orders", "taskKind", "PAGE_MAP", "executorProvider", "CODEX",
                "title", "t", "objective", "o", "targets", List.of(Map.of("targetType", "PROJECT", "targetKey", "orders"))));
        ArgumentCaptor<CreateTaskCommand> created = ArgumentCaptor.forClass(CreateTaskCommand.class);
        verify(tasks).create(created.capture());
        assertEquals("builder", created.getValue().createdBy());
    }

    private final ConnectionIdentity connection = new ConnectionIdentity(41L, "CODEX", actor);

    private void taskInProject(String projectCode) {
        TaskView task = mock(TaskView.class);
        when(task.projectCode()).thenReturn(projectCode);
        TaskDetailView detail = mock(TaskDetailView.class);
        when(detail.task()).thenReturn(task);
        when(tasks.detail("T-1")).thenReturn(detail);
    }

    @Test
    void claimNeedsWriteAccessToTheTaskProject() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest("POST", "/mcp/platform")));
        taskInProject("finance");
        assertThrows(ResponseStatusException.class, () -> adapter.claim(connection, "T-1", "rhc_x"));
        verifyNoInteractions(claims);
    }

    @Test
    void wrongClaimCodeComesBackAsAnAiCodingRefusalWithItsMessage() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest("POST", "/mcp/platform")));
        taskInProject("orders");
        when(claims.claim(eq("T-1"), eq("rhc_bad"), eq(connection), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "activation code is invalid"));
        ResponseEntity<?> response = adapter.claim(connection, "T-1", "rhc_bad");
        assertEquals(401, response.getStatusCode().value());
        assertEquals(Map.of("code", "MCP_AI_CODING_CLAIM_CODE_INVALID", "message", "activation code is invalid"), response.getBody());
    }

    @Test
    void agentStepsRequireThisConnectionsClaimAndFillTheSchema() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest("POST", "/mcp/platform")));
        taskInProject("orders");
        when(guard.requireConnectionTaskAccess("T-1", 41L))
                .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "this connection has not claimed the task"));
        ResponseEntity<?> refused = adapter.agent(connection, "T-1", McpPlatformAiCodingPort.Agent.CONTEXT, Map.of());
        assertEquals("MCP_AI_CODING_TASK_NOT_CLAIMED", ((Map<?, ?>) refused.getBody()).get("code"));
        verify(tasks, never()).context(any(), any());

        reset(guard);
        adapter.agent(connection, "T-1", McpPlatformAiCodingPort.Agent.EVENT,
                Map.of("clientEventId", "e1", "eventType", "STARTED", "message", "开始接入"));
        ArgumentCaptor<EventCommand> event = ArgumentCaptor.forClass(EventCommand.class);
        verify(tasks).recordEvent(eq("T-1"), event.capture());
        assertEquals("reachai.ai-coding.event.v1", event.getValue().schema());
        assertEquals("开始接入", event.getValue().message());
        verify(guard).requireConnectionTaskAccess("T-1", 41L);
    }
}
