package com.enterprise.ai.control.aicoding.api;

import com.enterprise.ai.control.aicoding.application.AiCodingHandoffApplicationService;
import com.enterprise.ai.control.aicoding.application.AiCodingManagedExecutionService;
import com.enterprise.ai.control.aicoding.application.AiCodingManagedExecutionStreamService;
import com.enterprise.ai.control.aicoding.application.AiCodingTaskApplicationService;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.CreateTaskCommand;
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
import static org.mockito.Mockito.*;

class AiCodingTaskMcpPlatformAdapterTest {
    private final AiCodingTaskApplicationService tasks = mock(AiCodingTaskApplicationService.class);
    private final AiCodingTaskMcpPlatformAdapter adapter = new AiCodingTaskMcpPlatformAdapter(new AiCodingTaskConsoleController(tasks,
            mock(AiCodingHandoffApplicationService.class), mock(AiCodingManagedExecutionService.class), mock(AiCodingManagedExecutionStreamService.class)),
            new PlatformDelegatedRequestScope(), new ObjectMapper());
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
}
