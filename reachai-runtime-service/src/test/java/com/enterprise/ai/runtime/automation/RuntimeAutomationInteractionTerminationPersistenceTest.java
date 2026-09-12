package com.enterprise.ai.runtime.automation;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.enterprise.ai.runtime.execution.RuntimeInteractionEventEntity;
import com.enterprise.ai.runtime.execution.RuntimeInteractionEventMapper;
import com.enterprise.ai.runtime.execution.RuntimeInteractionSessionEntity;
import com.enterprise.ai.runtime.execution.RuntimeInteractionSessionMapper;
import com.enterprise.ai.runtime.execution.RuntimeWorkflowInteractionSessionService;
import com.enterprise.ai.runtime.identity.WorkflowExecutionIdentity;
import com.enterprise.ai.runtime.support.RuntimeQueryTestDatabase;
import com.enterprise.ai.runtime.trace.RuntimeTraceSpanEntity;
import com.enterprise.ai.runtime.trace.RuntimeTraceSpanMapper;
import com.enterprise.ai.runtime.trace.RuntimeTraceSpanTerminationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RuntimeAutomationInteractionTerminationPersistenceTest {
    private static final String CODE = "AUTOMATION_INTERACTION_REQUIRED";
    private static final WorkflowExecutionIdentity IDENTITY =
            WorkflowExecutionIdentity.fromAutomation("tenant-1", 8L, "orders", "automation-principal");
    private final ObjectMapper json = new ObjectMapper();
    private RuntimeQueryTestDatabase database;
    private AnnotationConfigApplicationContext context;
    private RuntimeInteractionSessionMapper sessions;
    private RuntimeInteractionEventMapper events;
    private RuntimeTraceSpanMapper spans;
    private RuntimeAutomationInteractionTerminationService termination;

    @BeforeEach
    void transactionalServicesWithActualPersistence() throws Exception {
        database = new RuntimeQueryTestDatabase(List.of("runtime_interaction_session", "runtime_interaction_event",
                "runtime_trace_span"), RuntimeInteractionSessionMapper.class, RuntimeInteractionEventMapper.class,
                RuntimeTraceSpanMapper.class);
        sessions = spy(database.mapper(RuntimeInteractionSessionMapper.class));
        events = spy(database.mapper(RuntimeInteractionEventMapper.class));
        spans = spy(database.mapper(RuntimeTraceSpanMapper.class));
        context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("automation-test",
                Map.of("reachai.runtime.automation.enabled", "true")));
        context.register(TransactionConfiguration.class);
        context.registerBean(DataSourceTransactionManager.class,
                () -> new DataSourceTransactionManager(database.jdbc().getDataSource()));
        context.registerBean(RuntimeWorkflowInteractionSessionService.class,
                () -> new RuntimeWorkflowInteractionSessionService(sessions, events, json));
        context.registerBean(RuntimeTraceSpanTerminationService.class,
                () -> new RuntimeTraceSpanTerminationService(spans));
        context.registerBean(RuntimeAutomationInteractionTerminationService.class,
                () -> new RuntimeAutomationInteractionTerminationService(
                        context.getBean(RuntimeWorkflowInteractionSessionService.class),
                        context.getBean(RuntimeTraceSpanTerminationService.class)));
        context.refresh();
        termination = context.getBean(RuntimeAutomationInteractionTerminationService.class);
        session("interaction-1", "trace-1", "WAITING_USER");
    }

    @AfterEach
    void close() {
        if (context != null) context.close();
        if (database != null) database.close();
    }

    @Test
    void cancellationAndTraceClosureAreScopedAndIdempotent() throws Exception {
        var waiting = span("trace-1", "WAITING_USER", null);
        var approval = span("trace-1", "WAITING_APPROVAL", null);
        var running = span("trace-1", "RUNNING", null);
        var successful = span("trace-1", "SUCCESS", null);
        var ended = span("trace-1", "WAITING_USER", LocalDateTime.of(2026, 9, 1, 0, 0));
        var foreign = span("trace-2", "WAITING_APPROVAL", null);

        termination.terminate(" interaction-1 ", "trace-1", IDENTITY);
        var cancelled = sessions.selectById("interaction-1");
        assertEquals("CANCELLED", cancelled.getStatus());
        assertEquals(8, cancelled.getRevision());
        assertEquals(Map.of("code", CODE, "status", "CANCELLED", "reason", "AUTOMATION_FAIL_CLOSED"),
                json.readValue(cancelled.getResultJson(), Map.class));
        var event = events.selectList(null).get(0);
        assertEquals("CANCELLED", event.getEventType());
        assertEquals("automation-principal", event.getOperatorId());
        assertEquals(cancelled.getUpdateTime(), event.getCreateTime());
        assertEquals(Map.of("code", CODE, "reason", "AUTOMATION_FAIL_CLOSED", "traceId", "trace-1"),
                json.readValue(event.getPayloadJson(), Map.class));
        for (var expected : List.of(waiting, approval)) {
            var closed = spans.selectById(expected.getId());
            assertEquals("ERROR", closed.getStatus());
            assertEquals(CODE, closed.getErrorCode());
            assertEquals(cancelled.getUpdateTime(), closed.getEndedAt());
            assertEquals(expected.getMetadataJson(), closed.getMetadataJson());
        }
        for (var unchanged : List.of(running, successful, ended, foreign)) {
            var actual = spans.selectById(unchanged.getId());
            assertEquals(unchanged.getStatus(), actual.getStatus());
            assertEquals(unchanged.getEndedAt(), actual.getEndedAt());
        }
        termination.terminate("interaction-1", "trace-1", IDENTITY);
        assertEquals(1L, events.selectCount(null));
        assertEquals(8, sessions.selectById("interaction-1").getRevision());
        assertEquals(cancelled.getUpdateTime(), spans.selectById(waiting.getId()).getEndedAt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Asia/Shanghai", "America/New_York", "UTC"})
    @org.junit.jupiter.api.parallel.ResourceLock("java.util.TimeZone.default")
    void cancellationUsesTheSameClockAsInteractionAndTraceCreation(String zone) {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(zone));
            LocalDateTime startedAt = LocalDateTime.now().minusSeconds(1);
            database.jdbc().update("UPDATE runtime_interaction_session SET create_time=?,update_time=? WHERE id=?",
                    startedAt, startedAt, "interaction-1");
            var waiting = span("trace-1", "WAITING_USER", null);
            database.jdbc().update("UPDATE runtime_trace_span SET started_at=? WHERE id=?", startedAt, waiting.getId());
            termination.terminate("interaction-1", "trace-1", IDENTITY);
            LocalDateTime endedAt = sessions.selectById("interaction-1").getUpdateTime();
            assertFalse(endedAt.isBefore(startedAt), "Cancellation cannot precede local interaction creation");
            assertFalse(endedAt.isAfter(LocalDateTime.now().plusSeconds(1)), "Cancellation cannot move into the future");
            assertEquals(endedAt, spans.selectById(waiting.getId()).getEndedAt());
            assertEquals(endedAt, events.selectList(null).get(0).getCreateTime());
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"foreign-interaction"})
    void missingOrForeignSessionDoesNotPreventOwnTraceClosure(String interactionId) {
        session("foreign-interaction", "trace-2", "WAITING_USER");
        var own = span("trace-1", "WAITING_USER", null);
        var foreign = span("trace-2", "WAITING_USER", null);
        termination.terminate(interactionId, "trace-1", null);
        assertEquals("WAITING_USER", sessions.selectById("interaction-1").getStatus());
        assertEquals("WAITING_USER", sessions.selectById("foreign-interaction").getStatus());
        assertEquals(0L, events.selectCount(null));
        assertEquals("ERROR", spans.selectById(own.getId()).getStatus());
        assertEquals("WAITING_USER", spans.selectById(foreign.getId()).getStatus());
    }

    @ParameterizedTest
    @ValueSource(strings = {"RESUMING", "COMPLETED", "CANCELLED", "EXPIRED"})
    void cancellationDoesNotOverwriteClaimedOrTerminalSessions(String status) {
        database.jdbc().update("UPDATE runtime_interaction_session SET status = ? WHERE id = ?",
                status, "interaction-1");
        termination.terminate("interaction-1", "trace-1", IDENTITY);
        var actual = sessions.selectById("interaction-1");
        assertEquals(status, actual.getStatus());
        assertEquals(7, actual.getRevision());
        assertEquals(0L, events.selectCount(null));
    }

    @Test
    void eventFailureRollsBackTheSessionAndLeavesTraceOpen() {
        var waiting = span("trace-1", "WAITING_USER", null);
        doThrow(new IllegalStateException("event-write-failed"))
                .when(events).insert(any(RuntimeInteractionEventEntity.class));
        assertEquals("event-write-failed", assertThrows(IllegalStateException.class,
                () -> termination.terminate("interaction-1", "trace-1", IDENTITY)).getMessage());
        assertUnchangedAfterRollback(waiting);
    }

    @Test
    void traceFailureRollsBackBothTheSessionAndItsEvent() {
        var waiting = span("trace-1", "WAITING_APPROVAL", null);
        doThrow(new IllegalStateException("trace-write-failed"))
                .when(spans).update(isNull(), any(Wrapper.class));
        assertEquals("trace-write-failed", assertThrows(IllegalStateException.class,
                () -> termination.terminate("interaction-1", "trace-1", IDENTITY)).getMessage());
        assertUnchangedAfterRollback(waiting);
    }

    @ParameterizedTest
    @ValueSource(strings = {"WAITING_USER", "RESUMING"})
    void cancellationUsesCurrentRevisionAndRespectsAConcurrentResume(String concurrentStatus) throws Exception {
        var beforeCancellationWrite = new CountDownLatch(1);
        var allowCancellationWrite = new CountDownLatch(1);
        doAnswer(invocation -> {
            beforeCancellationWrite.countDown();
            assertTrue(allowCancellationWrite.await(5, TimeUnit.SECONDS));
            return invocation.callRealMethod();
        }).when(sessions).update(isNull(), any(Wrapper.class));
        var executor = Executors.newSingleThreadExecutor();
        try {
            var cancellation = executor.submit(() -> termination.terminate("interaction-1", "trace-1", IDENTITY));
            assertTrue(beforeCancellationWrite.await(5, TimeUnit.SECONDS));
            // A separate JDBC connection commits a newer state before cancellation writes.
            assertEquals(1, database.jdbc().update(
                    "UPDATE runtime_interaction_session SET revision = revision + 2, status = ? WHERE id = ?",
                    concurrentStatus, "interaction-1"));
            allowCancellationWrite.countDown();
            cancellation.get(5, TimeUnit.SECONDS);
            boolean stillWaiting = "WAITING_USER".equals(concurrentStatus);
            assertEquals(stillWaiting ? "CANCELLED" : "RESUMING", sessions.selectById("interaction-1").getStatus());
            assertEquals(stillWaiting ? 10 : 9, sessions.selectById("interaction-1").getRevision());
            assertEquals(stillWaiting ? 1L : 0L, events.selectCount(null));
        } finally {
            allowCancellationWrite.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private void assertUnchangedAfterRollback(RuntimeTraceSpanEntity waiting) {
        assertEquals("WAITING_USER", sessions.selectById("interaction-1").getStatus());
        assertEquals(7, sessions.selectById("interaction-1").getRevision());
        assertEquals(0L, events.selectCount(null));
        assertEquals(waiting.getStatus(), spans.selectById(waiting.getId()).getStatus());
        assertNull(spans.selectById(waiting.getId()).getEndedAt());
    }

    private void session(String id, String traceId, String status) {
        var row = new RuntimeInteractionSessionEntity();
        row.setId(id);
        row.setTraceId(traceId);
        row.setStatus(status);
        row.setRevision(7);
        row.setSourceType("WORKFLOW");
        row.setNodeId("approval-node");
        row.setInteractionType("CONFIRM_ACTION");
        sessions.insert(row);
    }

    private RuntimeTraceSpanEntity span(String traceId, String status, LocalDateTime endedAt) {
        var row = new RuntimeTraceSpanEntity();
        row.setTraceId(traceId);
        row.setSpanId("span-" + java.util.UUID.randomUUID());
        row.setSpanType("WORKFLOW");
        row.setStatus(status);
        row.setEndedAt(endedAt);
        row.setMetadataJson("{\"nodeId\":\"approval-node\"}");
        spans.insert(row);
        return row;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TransactionConfiguration { }
}
