package com.enterprise.ai.control.aicoding.api;

import com.enterprise.ai.control.aicoding.application.AiCodingHandoffApplicationService;
import com.enterprise.ai.control.aicoding.application.AiCodingManagedExecutionService;
import com.enterprise.ai.control.aicoding.application.AiCodingManagedExecutionStreamService;
import com.enterprise.ai.control.aicoding.application.AiCodingTaskApplicationService;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformConsoleAuthInterceptor;
import com.enterprise.ai.control.identity.PlatformUserEntity;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AiCodingTaskConsoleManagedExecutionRouteTest {

    @Test
    void exposesAuthenticatedNonBufferedManagedExecutionSseRoute() throws Exception {
        AiCodingTaskApplicationService taskService = mock(AiCodingTaskApplicationService.class);
        AiCodingHandoffApplicationService handoffService = mock(AiCodingHandoffApplicationService.class);
        AiCodingManagedExecutionService managedService = mock(AiCodingManagedExecutionService.class);
        AiCodingManagedExecutionStreamService streamService = mock(AiCodingManagedExecutionStreamService.class);
        SseEmitter emitter = new SseEmitter(5_000L);
        when(streamService.stream("task-1", "99")).thenReturn(emitter);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new AiCodingTaskConsoleController(
                taskService, handoffService, managedService, streamService)).build();

        PlatformUserEntity user = new PlatformUserEntity();
        user.setId(99L);
        user.setUsername("operator");
        PlatformAuthenticatedSession session = new PlatformAuthenticatedSession(
                user,
                "session-1",
                LocalDateTime.now().plusMinutes(5),
                List.of("ADMIN"),
                List.of(),
                List.of());

        MvcResult initial = mvc.perform(get("/api/ai-coding-console/tasks/task-1/managed-execution/events")
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .requestAttr(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE, session))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();

        emitter.send(SseEmitter.event().name("managed.snapshot").data("{}"));
        emitter.complete();
        mvc.perform(asyncDispatch(initial))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Accel-Buffering", "no"));

        verify(streamService).stream("task-1", "99");
    }
}
