package com.enterprise.ai.runtime.trace;

import com.enterprise.ai.runtime.agent.RuntimeAgentSkillBindingSnapshot;
import com.enterprise.ai.runtime.runops.RuntimeRunLifecycleService;
import com.enterprise.ai.runtime.supervisor.SupervisorExecutionTraceService;
import com.enterprise.ai.runtime.support.RuntimeQueryTestDatabase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RuntimeTraceMetadataPersistenceTest {
    private static final LocalDateTime START = LocalDateTime.of(2026, 9, 6, 12, 0);
    private RuntimeQueryTestDatabase database;
    private RuntimeTraceSpanMapper spans;
    private RuntimeTraceRootService roots;
    private SupervisorExecutionTraceService supervisor;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        database = new RuntimeQueryTestDatabase(List.of("runtime_trace_span"), RuntimeTraceSpanMapper.class);
        spans = spy(database.mapper(RuntimeTraceSpanMapper.class));
        var proxy = new org.springframework.aop.framework.ProxyFactory(new RuntimeTraceRootService(spans, json));
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(database.jdbc().getDataSource()),
                new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        roots = (RuntimeTraceRootService) proxy.getProxy();
        supervisor = new SupervisorExecutionTraceService(
                new com.enterprise.ai.runtime.trace.RuntimeTraceEvidenceWriter(spans, mock(RuntimeToolCallLogMapper.class)),
                mock(RuntimeRunLifecycleService.class),
                json,
                roots,
                new RuntimeTraceSpanTerminationService(spans));
        database.jdbc().update("""
                INSERT INTO runtime_trace_span
                  (id, trace_id, span_id, span_type, status, started_at, metadata_json)
                VALUES (1, 'trace-1', 'root-1', 'SUPERVISOR', 'RUNNING', ?, ?)
                """, START, "{\"agentConfigVersionId\":41,\"policyProfile\":\"SAFE\"}");
    }

    @AfterEach
    void close() { if (database != null) database.close(); }

    @Test
    void lateSkillSnapshotPreservesCommittedTerminalStateAndOtherMetadata() throws Exception {
        independentlyExecute("""
                        UPDATE runtime_trace_span SET status = 'CANCELLED', error_code = 'CANCELLED',
                          error_message = 'terminal evidence', ended_at = '2026-09-06 12:01:00',
                          metadata_json = '{"agentConfigVersionId":41,"concurrentMarker":"已取消"}'
                        WHERE id = 1
                        """);

        supervisor.skillBindings(handle("trace-1", "root-1"), List.of(binding()));

        var stored = spans.selectById(1L);
        assertEquals("CANCELLED", stored.getStatus());
        assertEquals("CANCELLED", stored.getErrorCode());
        assertEquals("terminal evidence", stored.getErrorMessage());
        assertEquals(START.plusMinutes(1), stored.getEndedAt());
        JsonNode metadata = json.readTree(stored.getMetadataJson());
        assertEquals("已取消", metadata.path("concurrentMarker").asText());
        assertEquals(1, metadata.path("skillBindingCount").asInt());
        assertEquals("reachai/测试技能@1.2.3#" + "a".repeat(64),
                metadata.path("skillBindings").get(0).path("identity").asText());
        assertFalse(stored.getMetadataJson().contains("private-skill-body"));
    }

    @ParameterizedTest
    @CsvSource({"other-trace,root-1", "trace-1,other-root"})
    void validDatabaseIdCannotAttachBindingsToADifferentRoot(String traceId, String spanId) {
        String original = spans.selectById(1L).getMetadataJson();
        supervisor.skillBindings(handle(traceId, spanId), List.of(binding()));
        assertEquals(original, spans.selectById(1L).getMetadataJson());
    }

    @ParameterizedTest
    @CsvSource({"SUPERVISOR,parent-1", "WORKFLOW,null"})
    void supervisorBindingsCannotBeAttachedToChildrenOrOtherRootTypes(String type, String parent) {
        database.jdbc().update("UPDATE runtime_trace_span SET span_type = ?, parent_span_id = ? WHERE id = 1",
                type, "null".equals(parent) ? null : parent);
        String original = spans.selectById(1L).getMetadataJson();
        supervisor.skillBindings(handle("trace-1", "root-1"), List.of(binding()));
        assertEquals(original, spans.selectById(1L).getMetadataJson());
    }

    @Test
    void completionRetainsPinnedConfigurationAndSkillEvidence() throws Exception {
        supervisor.skillBindings(handle("trace-1", "root-1"), List.of(binding()));

        supervisor.finish(handle("trace-1", "root-1"), true, "OK", "private-answer", Map.of("tokenCost", 9));

        var stored = spans.selectById(1L);
        assertEquals("SUCCESS", stored.getStatus());
        JsonNode metadata = json.readTree(stored.getMetadataJson());
        assertEquals(41, metadata.path("agentConfigVersionId").asInt());
        assertEquals("SAFE", metadata.path("policyProfile").asText());
        assertEquals(1, metadata.path("skillBindingCount").asInt());
        assertEquals(9, metadata.path("tokenCost").asInt());
        assertFalse(stored.getOutputSummary().contains("private-answer"));
    }

    @Test
    void completionAndSkillMetadataSerializeWithoutLosingEitherUpdate() throws Exception {
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var first = new java.util.concurrent.atomic.AtomicBoolean(true);
        var pool = Executors.newFixedThreadPool(2);
        doAnswer(call -> {
            Object row = call.callRealMethod();
            if (first.getAndSet(false)) {
                locked.countDown();
                assertTrue(release.await(10, TimeUnit.SECONDS));
            }
            return row;
        }).when(spans).selectByIdForUpdate(1L);
        try {
            var completed = pool.submit(() -> roots.finish(
                    new RuntimeTraceRootService.Handle(1L, "trace-1", "root-1", START),
                    new RuntimeTraceRootService.Completion("SUCCESS", "OK", "private-answer",
                            START.plusSeconds(2), "{\"tokenCost\":7,\"concurrentMarker\":\"新证据\"}")));
            assertTrue(locked.await(10, TimeUnit.SECONDS));
            var bound = pool.submit(() -> supervisor.skillBindings(handle("trace-1", "root-1"), List.of(binding())));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boolean blocked = false;
            while (System.nanoTime() < deadline) {
                if (database.jdbc().queryForObject(
                        "SELECT COUNT(*) FROM information_schema.sessions WHERE blocker_id IS NOT NULL", Integer.class) > 0) {
                    blocked = true;
                    break;
                }
                Thread.sleep(10);
            }
            assertTrue(blocked, "Skill metadata must wait for the root completion transaction");
            release.countDown();
            assertTrue(completed.get(10, TimeUnit.SECONDS));
            bound.get(10, TimeUnit.SECONDS);
            var stored = spans.selectById(1L);
            JsonNode metadata = json.readTree(stored.getMetadataJson());
            assertEquals("新证据", metadata.path("concurrentMarker").asText());
            assertEquals(41, metadata.path("agentConfigVersionId").asInt());
            assertEquals(7, metadata.path("tokenCost").asInt());
            assertEquals(1, metadata.path("skillBindingCount").asInt());
            assertEquals("SUCCESS", stored.getStatus());
            assertEquals(START.plusSeconds(2), stored.getEndedAt());
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void metadataWriteFailureRollsBackItsActualSqlUpdate() {
        String original = spans.selectById(1L).getMetadataJson();
        doAnswer(call -> {
            call.callRealMethod();
            throw new org.springframework.dao.DataAccessResourceFailureException("injected write failure");
        }).when(spans).update(isNull(), any());
        assertThrows(org.springframework.dao.DataAccessResourceFailureException.class, () -> roots.recordSkillBindings(
                new RuntimeTraceRootService.Handle(1L, "trace-1", "root-1", START), "[]"));
        assertEquals(original, spans.selectById(1L).getMetadataJson());
    }

    private SupervisorExecutionTraceService.TraceHandle handle(String traceId, String spanId) {
        return new SupervisorExecutionTraceService.TraceHandle(traceId, spanId, 1L, START);
    }

    private RuntimeAgentSkillBindingSnapshot binding() {
        RuntimeAgentSkillBindingSnapshot binding = RuntimeAgentSkillBindingSnapshot.builder()
                .skillId(11L)
                .skillVersionId(21L)
                .publisher("reachai")
                .standardName("测试技能")
                .version("1.2.3")
                .sourceSha256("a".repeat(64))
                .activationMode("MODEL_SELECTED")
                .scriptPolicy("DENY")
                .required(true)
                .packageManifestJson("{\"instruction\":\"private-skill-body\"}")
                .build();
        return binding;
    }

    private void independentlyExecute(String sql) throws Exception {
        try (var connection = database.jdbc().getDataSource().getConnection();
             var statement = connection.createStatement()) {
            connection.setAutoCommit(true);
            statement.executeUpdate(sql);
        }
    }
}
