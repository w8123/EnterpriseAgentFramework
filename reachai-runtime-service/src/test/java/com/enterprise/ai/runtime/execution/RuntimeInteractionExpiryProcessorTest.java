package com.enterprise.ai.runtime.execution;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.enterprise.ai.runtime.supervisor.SupervisorExecutionTraceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeInteractionExpiryProcessorTest {

    private final RuntimeInteractionSessionMapper sessionMapper = mock(RuntimeInteractionSessionMapper.class);
    private final RuntimeInteractionEventMapper eventMapper = mock(RuntimeInteractionEventMapper.class);
    private final SupervisorExecutionTraceService traceService = mock(SupervisorExecutionTraceService.class);
    private RuntimeInteractionExpiryProcessor processor;

    @BeforeEach
    void setUp() {
        RuntimeWorkflowInteractionSessionService sessionService =
                new RuntimeWorkflowInteractionSessionService(sessionMapper, eventMapper, new ObjectMapper());
        processor = new RuntimeInteractionExpiryProcessor(sessionMapper, sessionService, traceService);
    }

    @Test
    void casWinnerExpiresSessionAndClosesTraceAndRun() {
        LocalDateTime now = LocalDateTime.now();
        RuntimeInteractionSessionEntity session = expiredSession(now);
        when(sessionMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        assertTrue(processor.expireOne(session, now));

        verify(eventMapper).insert(any(RuntimeInteractionEventEntity.class));
        verify(traceService).expireWaitingInteraction("trace-1", "wfi-expired", now);
    }

    @Test
    void casLoserDoesNotWriteEventOrCloseRun() {
        LocalDateTime now = LocalDateTime.now();
        RuntimeInteractionSessionEntity session = expiredSession(now);
        when(sessionMapper.update(isNull(), any(Wrapper.class))).thenReturn(0);

        assertFalse(processor.expireOne(session, now));

        verify(eventMapper, never()).insert(any(RuntimeInteractionEventEntity.class));
        verify(traceService, never()).expireWaitingInteraction(any(), any(), any());
    }

    private RuntimeInteractionSessionEntity expiredSession(LocalDateTime now) {
        RuntimeInteractionSessionEntity session = new RuntimeInteractionSessionEntity();
        session.setId("wfi-expired");
        session.setStatus("WAITING_USER");
        session.setRevision(3);
        session.setTraceId("trace-1");
        session.setExpiresAt(now.minusSeconds(1));
        return session;
    }
}
