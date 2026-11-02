package com.enterprise.ai.control.aicoding.api;

import com.enterprise.ai.control.aicoding.application.AiCodingHandoffApplicationService;
import com.enterprise.ai.control.aicoding.application.AiCodingManagedExecutionService;
import com.enterprise.ai.control.aicoding.application.AiCodingManagedExecutionStreamService;
import com.enterprise.ai.control.aicoding.application.AiCodingTaskApplicationService;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskDetailView;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskView;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformConsoleAuthInterceptor;
import com.enterprise.ai.control.identity.PlatformPermissionGrant;
import com.enterprise.ai.control.identity.PlatformPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AiCodingTaskConsoleScopeTest {
    private final AiCodingTaskApplicationService tasks = mock(AiCodingTaskApplicationService.class);
    private final AiCodingManagedExecutionService managed = mock(AiCodingManagedExecutionService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new AiCodingTaskConsoleController(tasks,
            mock(AiCodingHandoffApplicationService.class), managed, mock(AiCodingManagedExecutionStreamService.class))).build();
    // Platform read and write only inside project "orders".
    private final PlatformAuthenticatedSession session = new PlatformAuthenticatedSession(new PlatformPrincipal(7L, "builder", "Builder"),
            "s", LocalDateTime.now().plusHours(1), List.of(), List.of("platform:read", "platform:write"),
            List.of(new PlatformPermissionGrant("platform:read", "PROJECT", "orders"), new PlatformPermissionGrant("platform:write", "PROJECT", "orders")));

    @Test
    void anotherProjectsTaskCannotBeReadOrCancelled() throws Exception {
        stubTask("t-fin", "finance");
        mvc.perform(get("/api/ai-coding-console/tasks/t-fin").requestAttr(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE, session))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/ai-coding-console/tasks/t-fin/cancel").contentType(MediaType.APPLICATION_JSON).content("{}")
                .requestAttr(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE, session)).andExpect(status().isForbidden());
        verify(tasks, never()).cancel(any(), any());
        verify(managed, never()).cancel(any(), any());
    }

    @Test
    void listShowsOnlyReadableProjectsAndActorIsTheSession() throws Exception {
        TaskView orders = view("orders"), finance = view("finance");
        when(tasks.list(isNull(), isNull(), isNull(), isNull(), eq(100))).thenReturn(List.of(orders, finance));
        mvc.perform(get("/api/ai-coding-console/tasks").requestAttr(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE, session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));

        stubTask("t-ord", "orders");
        mvc.perform(post("/api/ai-coding-console/tasks/t-ord/cancel").contentType(MediaType.APPLICATION_JSON).content("{\"actor\":\"someone\"}")
                .requestAttr(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE, session)).andExpect(status().isOk());
        verify(tasks).cancel("t-ord", "builder");
    }

    private void stubTask(String id, String project) {
        TaskDetailView detail = mock(TaskDetailView.class);
        TaskView view = view(project);
        when(detail.task()).thenReturn(view);
        when(tasks.detail(id)).thenReturn(detail);
    }

    private static TaskView view(String project) {
        TaskView view = mock(TaskView.class);
        when(view.projectCode()).thenReturn(project);
        return view;
    }
}
