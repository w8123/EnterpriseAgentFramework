package com.enterprise.ai.control.a2a.application.task;

import com.enterprise.ai.control.a2a.application.port.A2aTaskManagementReader;
import com.enterprise.ai.control.a2a.application.port.A2aTaskManagementReader.EventRow;
import com.enterprise.ai.control.a2a.application.port.A2aTaskManagementReader.TaskRow;
import com.enterprise.ai.control.a2a.application.outbound.A2aOutboundDelegationService;
import com.enterprise.ai.control.a2a.domain.A2aDomainException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class A2aTaskManagementServiceTest {

    private final A2aTaskManagementReader reader = mock(A2aTaskManagementReader.class);
    private final A2aTaskDispatchService dispatch = mock(A2aTaskDispatchService.class);
    private final A2aOutboundDelegationService outbound = mock(A2aOutboundDelegationService.class);
    private final A2aTaskManagementService service =
            new A2aTaskManagementService(reader, dispatch, outbound);

    @Test
    void createsAStableBoundedManagementQuery() {
        when(reader.findPage(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new A2aTaskManagementReader.Page(List.of(task("task-1", "INBOUND", 1)), 1));

        var result = service.list(
                "  trace-1  ", "inbound", "task_state_working",
                10L, null, 20L, " tenant-a ", null, null, 999, -1);

        assertEquals(1, result.items().size());
        assertEquals(200, result.limit());
        assertEquals(0, result.offset());
        ArgumentCaptor<A2aTaskManagementReader.Query> query =
                ArgumentCaptor.forClass(A2aTaskManagementReader.Query.class);
        verify(reader).findPage(query.capture());
        assertEquals("trace-1", query.getValue().search());
        assertEquals("INBOUND", query.getValue().direction());
        assertEquals("TASK_STATE_WORKING", query.getValue().state());
        assertEquals("tenant-a", query.getValue().tenantScope());
    }

    @Test
    void detectsAnEventSequenceGapInsteadOfInventingACompleteTimeline() {
        TaskRow task = task("task-1", "INBOUND", 3);
        when(reader.findByTaskId("task-1", "INBOUND")).thenReturn(List.of(task));
        when(reader.findEvents(11L)).thenReturn(List.of(event(1), event(3)));
        when(reader.findMessages(11L)).thenReturn(List.of());
        when(reader.findArtifacts(11L)).thenReturn(List.of());

        var detail = service.detail("task-1", "inbound");

        assertTrue(detail.eventGapDetected());
        assertEquals("principal-a", detail.identity().principalKey());
        assertEquals("trace-1", detail.runtime().traceId());
    }

    @Test
    void requiresDirectionWhenTheProtocolTaskIdExistsInBothDirections() {
        when(reader.findByTaskId("same-task", null)).thenReturn(List.of(
                task("same-task", "INBOUND", 1),
                task("same-task", "OUTBOUND", 1)));

        A2aDomainException failure = assertThrows(A2aDomainException.class,
                () -> service.detail("same-task", null));

        assertEquals("A2A_TASK_ID_AMBIGUOUS", failure.code());
    }

    @Test
    void managementCancelUsesTheDurableDispatchBoundary() {
        TaskRow task = task("task-1", "INBOUND", 1);
        when(reader.findByTaskId("task-1", "INBOUND")).thenReturn(List.of(task));

        service.cancel("task-1", "INBOUND", "operator-1");

        verify(dispatch).requestCancellation("exec-1", "PLATFORM_USER", "operator-1");
        verify(reader, times(2)).findByTaskId("task-1", "INBOUND");
    }

    @Test
    void outboundCancelUsesTheRemoteTaskLifecycleBoundary() {
        TaskRow task = task("task-2", "OUTBOUND", 1);
        when(reader.findByTaskId("task-2", "OUTBOUND")).thenReturn(List.of(task));

        service.cancel("task-2", "OUTBOUND", "operator-1");

        verify(outbound).cancel("exec-1", "PLATFORM_USER", "operator-1");
        verify(reader, times(2)).findByTaskId("task-2", "OUTBOUND");
    }

    private TaskRow task(String taskId, String direction, long lastEventSequence) {
        LocalDateTime now = LocalDateTime.of(2026, 8, 24, 0, 0);
        return new TaskRow(
                11L, taskId, direction, "TASK_STATE_WORKING", "ctx-1",
                "INBOUND".equals(direction) ? 21L : null,
                "INBOUND".equals(direction) ? "publication-a" : null,
                "INBOUND".equals(direction) ? 22L : null,
                "OUTBOUND".equals(direction) ? 31L : null,
                "OUTBOUND".equals(direction) ? "remote-a" : null,
                "OUTBOUND".equals(direction) ? 32L : null,
                41L, "principal-a", "Partner Agent", "tenant-a",
                51L, "trust-a", "exec-1", "run-1", "trace-1", null,
                "1 text Part", null, null, null,
                "OUTBOUND".equals(direction) ? "ACTIVE" : null,
                "OUTBOUND".equals(direction) ? 2 : 0,
                "OUTBOUND".equals(direction) ? now.plusSeconds(2) : null,
                "OUTBOUND".equals(direction) ? now.minusSeconds(2) : null,
                null, null, 1, lastEventSequence,
                now, now, null, now.plusMinutes(2), now.plusDays(30), now);
    }

    private EventRow event(long sequence) {
        return new EventRow(
                sequence, "event-" + sequence, "TASK_STATE_CHANGED",
                sequence == 1 ? null : "TASK_STATE_SUBMITTED", "TASK_STATE_WORKING",
                "SYSTEM", "a2a-hub", "TASK", "task-1", "state changed",
                sequence, "trace-1", LocalDateTime.of(2026, 8, 24, 0, 0).plusSeconds(sequence));
    }
}
