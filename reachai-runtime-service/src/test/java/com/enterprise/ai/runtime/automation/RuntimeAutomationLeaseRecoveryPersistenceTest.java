package com.enterprise.ai.runtime.automation;

import com.enterprise.ai.common.testing.ClonedDevelopmentMysqlDatabase;
import com.enterprise.ai.runtime.runops.RuntimeRunMapper;
import com.enterprise.ai.runtime.runops.RuntimeRunLifecycleService;
import com.enterprise.ai.runtime.support.RuntimeQueryTestDatabase;
import com.enterprise.ai.runtime.trace.RuntimeTraceSpanMapper;
import com.enterprise.ai.runtime.trace.RuntimeTraceSpanTerminationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class RuntimeAutomationLeaseRecoveryPersistenceTest {
    private static final List<String> TABLES = List.of("runtime_automation", "runtime_automation_version",
            "runtime_automation_occurrence", "runtime_automation_attempt", "runtime_automation_event",
            "runtime_automation_execution_slot", "runtime_run", "runtime_trace_span");
    private RuntimeQueryTestDatabase database;
    private ClonedDevelopmentMysqlDatabase mysql;
    private RuntimeAutomationOccurrenceMapper occurrences;
    private RuntimeAutomationAttemptMapper attempts;
    private RuntimeAutomationExecutionPersistenceService persistence;
    private RuntimeAutomationWorker worker;
    private RuntimeAutomationTargetExecutor target;
    private AnnotationConfigApplicationContext context;

    @BeforeEach
    void open() throws Exception {
        Class<?>[] mappers = {RuntimeAutomationMapper.class, RuntimeAutomationVersionMapper.class,
                RuntimeAutomationOccurrenceMapper.class, RuntimeAutomationAttemptMapper.class,
                RuntimeAutomationEventMapper.class, RuntimeAutomationExecutionSlotMapper.class,
                RuntimeRunMapper.class, RuntimeTraceSpanMapper.class};
        if (Boolean.getBoolean("reachai.mysql.automationLeaseVerification")) {
            mysql = new ClonedDevelopmentMysqlDatabase("reachai.mysql.automationLeaseVerification",
                    "audit_automation_lease", "runtime_", TABLES);
            database = new RuntimeQueryTestDatabase(mysql, mappers);
        } else {
            database = new RuntimeQueryTestDatabase(TABLES, mappers);
            database.jdbc().execute("CREATE ALIAS IF NOT EXISTS UTC_TIMESTAMP FOR \""
                    + RuntimeAutomationLeaseRecoveryPersistenceTest.class.getName() + ".databaseUtcTimestamp\"");
        }
        occurrences = database.mapper(RuntimeAutomationOccurrenceMapper.class);
        attempts = database.mapper(RuntimeAutomationAttemptMapper.class);
        var json = new RuntimeAutomationJsonSupport(new ObjectMapper());
        var service = new RuntimeAutomationExecutionPersistenceService(occurrences, attempts,
                database.mapper(RuntimeAutomationEventMapper.class), json,
                database.mapper(RuntimeAutomationExecutionSlotMapper.class),
                new RuntimeRunLifecycleService(database.mapper(RuntimeRunMapper.class), new ObjectMapper()),
                new RuntimeTraceSpanTerminationService(database.mapper(RuntimeTraceSpanMapper.class)),
                database.mapper(RuntimeAutomationMapper.class));
        context = new AnnotationConfigApplicationContext();
        context.registerBean(DataSourceTransactionManager.class,
                () -> new DataSourceTransactionManager(database.jdbc().getDataSource()));
        context.registerBean(RuntimeAutomationExecutionPersistenceService.class, () -> service);
        context.register(TransactionConfiguration.class);
        context.refresh();
        persistence = context.getBean(RuntimeAutomationExecutionPersistenceService.class);
        target = mock(RuntimeAutomationTargetExecutor.class);
        when(target.execute(any(), any(), any(), anyString(), anyMap())).thenAnswer(call ->
                new RuntimeAutomationTargetExecutor.ExecutionOutcome(true, false, call.getArgument(3),
                        "RUNTIME_GRAPH_EXECUTED", "recovered", Map.of(), false));
        var executor = mock(ThreadPoolTaskExecutor.class);
        doAnswer(call -> { ((Runnable) call.getArgument(0)).run(); return null; })
                .when(executor).execute(any(Runnable.class));
        worker = new RuntimeAutomationWorker(occurrences, database.mapper(RuntimeAutomationExecutionSlotMapper.class),
                database.mapper(RuntimeAutomationMapper.class), database.mapper(RuntimeAutomationVersionMapper.class),
                target, persistence, json, executor);
    }

    public static LocalDateTime utcTimestamp(int precision) { return LocalDateTime.now(Clock.systemUTC()); }

    // A different host clock must not defer work that the database has already declared expired.
    public static LocalDateTime databaseUtcTimestamp(int precision) { return utcTimestamp(precision).minusSeconds(30); }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TransactionConfiguration { }

    @AfterEach
    void close() {
        if (context != null) context.close();
        try { if (database != null) database.close(); }
        finally { if (mysql != null) mysql.close(); }
    }

    @Test
    void replacementWorkerClosesAbandonedAttemptAndRunBeforeSuccessfulRetry() {
        seed(2, true, false);
        var oldOccurrence = occurrences.selectById(301L);
        var oldAttempt = attempts.selectById(401L);
        worker.poll();
        assertEquals("SUCCEEDED", occurrences.selectById(301L).getStatus(), this::recoveryState);
        assertEquals(2, occurrences.selectById(301L).getAttemptCount());
        assertAbandonedClosed("FAILED");
        assertThrows(RuntimeAutomationExecutionPersistenceService.StaleAutomationLeaseException.class,
                () -> persistence.complete(oldOccurrence, oldAttempt, "old-lease",
                        new RuntimeAutomationTargetExecutor.ExecutionOutcome(true, false, "old-trace", null, "late", Map.of(), false)));
        assertAbandonedClosed("FAILED");
        assertEquals("SUCCEEDED", occurrences.selectById(301L).getStatus(), this::recoveryState);
        verify(target, times(1)).execute(any(), any(), any(), anyString(), anyMap());
    }

    @Test
    void exhaustedLeaseClosesAttemptRunAndSlotWithoutAnotherExecution() {
        seed(1, true, false);
        worker.poll();
        assertEquals("DEAD", occurrences.selectById(301L).getStatus());
        assertAbandonedClosed("DEAD");
        assertEquals(0, database.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_automation_execution_slot", Integer.class));
        verifyNoInteractions(target);
    }

    @Test
    void validLeaseRemainsOwnedByTheOtherWorker() {
        seed(2, false, false);
        worker.poll();
        assertEquals("RUNNING", occurrences.selectById(301L).getStatus());
        assertEquals("old-lease", occurrences.selectById(301L).getLeaseToken());
        assertEquals("RUNNING", attempts.selectById(401L).getStatus());
        assertEquals("RUNNING", database.jdbc().queryForObject("SELECT status FROM runtime_run WHERE trace_id='old-trace'", String.class));
        verifyNoInteractions(target);
    }

    @Test
    void completedBusinessEvidenceSurvivesLeaseRecovery() {
        seed(2, true, true);
        worker.poll();
        assertEquals("FAILED", attempts.selectById(401L).getStatus());
        assertEquals("COMPLETED", database.jdbc().queryForObject("SELECT status FROM runtime_run WHERE trace_id='old-trace'", String.class));
        assertEquals("SUCCESS", database.jdbc().queryForObject("SELECT status FROM runtime_trace_span WHERE trace_id='old-trace'", String.class));
        assertEquals("SUCCEEDED", occurrences.selectById(301L).getStatus(), this::recoveryState);
    }

    @Test
    void archivedAutomationDoesNotLeaveExpiredWorkQueuedForever() {
        seed(2, true, false);
        database.jdbc().update("UPDATE runtime_automation SET status='ARCHIVED' WHERE id=101");
        worker.poll();
        assertEquals("CANCELLED", occurrences.selectById(301L).getStatus());
        assertAbandonedClosed("FAILED");
        assertEquals(0, database.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_automation_execution_slot", Integer.class));
        verifyNoInteractions(target);
    }

    @Test
    void auditFailureRollsBackAttemptRunSpanAndSlotRecoveryTogether() {
        seed(2, true, false);
        var failure = new FailRecoveryAudit();
        database.addInterceptor(failure);
        assertThrows(RuntimeException.class, persistence::recoverExpiredExecutions);
        assertEquals("RUNNING", occurrences.selectById(301L).getStatus());
        assertEquals("RUNNING", attempts.selectById(401L).getStatus());
        assertEquals("RUNNING", database.jdbc().queryForObject("SELECT status FROM runtime_run WHERE trace_id='old-trace'", String.class));
        assertEquals("RUNNING", database.jdbc().queryForObject("SELECT status FROM runtime_trace_span WHERE trace_id='old-trace'", String.class));
        assertEquals(1, database.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_automation_execution_slot", Integer.class));
        failure.enabled.set(false);
        worker.poll();
        assertAbandonedClosed("FAILED");
        assertEquals("SUCCEEDED", occurrences.selectById(301L).getStatus(), this::recoveryState);
    }

    @Intercepts(@Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class}))
    static class FailRecoveryAudit implements Interceptor {
        final AtomicBoolean enabled = new AtomicBoolean(true);
        @Override public Object intercept(Invocation invocation) throws Throwable {
            if (enabled.get() && ((MappedStatement) invocation.getArgs()[0]).getId()
                    .equals(RuntimeAutomationEventMapper.class.getName() + ".insert")) {
                throw new IllegalStateException("injected recovery audit failure");
            }
            return invocation.proceed();
        }
    }

    private void assertAbandonedClosed(String attemptStatus) {
        var attempt = attempts.selectById(401L);
        assertEquals(attemptStatus, attempt.getStatus());
        assertNotNull(attempt.getEndedAt());
        assertEquals("AUTOMATION_LEASE_EXPIRED", attempt.getErrorCode());
        assertEquals("FAILED", database.jdbc().queryForObject("SELECT status FROM runtime_run WHERE trace_id='old-trace'", String.class));
        assertEquals("ERROR", database.jdbc().queryForObject("SELECT status FROM runtime_trace_span WHERE trace_id='old-trace'", String.class));
    }

    private String recoveryState() {
        return "applicationUtc=" + utcTimestamp(6) + ", database=" + database.jdbc().queryForList(
                "SELECT status,attempt_count,available_at,UTC_TIMESTAMP(6) AS database_utc,last_error_code,last_error_message "
                        + "FROM runtime_automation_occurrence WHERE id=301");
    }

    private void seed(int maxAttempts, boolean expired, boolean completedRun) {
        LocalDateTime now = utcTimestamp(6), lease = expired ? now.minusMinutes(1) : now.plusMinutes(5);
        database.jdbc().update("INSERT INTO runtime_automation (id,automation_key,name,status,created_by,updated_by) VALUES (101,'aut_recovery','recovery','ACTIVE','test','test')");
        database.jdbc().update("""
                INSERT INTO runtime_automation_version
                (id,automation_id,version_no,target_type,target_id,target_version_id,target_snapshot_json,trigger_type,
                 fire_at,time_zone,misfire_policy,concurrency_policy,input_json,principal_type,principal_id,
                 principal_snapshot_json,fingerprint_sha256,created_by)
                VALUES (201,101,1,'WORKFLOW','wf-recovery',1,'{}','ONCE',?,'UTC','SKIP','QUEUE','{}',
                        'AUTOMATION_SERVICE_ACCOUNT','recovery-principal','{}',?,'test')
                """, now, "a".repeat(64));
        database.jdbc().update("""
                INSERT INTO runtime_automation_occurrence
                (id,occurrence_key,automation_id,automation_version_id,source_type,scheduled_at,available_at,status,
                 max_attempts,attempt_count,lease_owner,lease_token,leased_until,trace_id,input_snapshot_json,principal_snapshot_json)
                VALUES (301,'recovery-occurrence',101,201,'MANUAL',?,?,'RUNNING',?,1,'old-worker','old-lease',?,'old-trace','{}','{}')
                """, now.minusMinutes(2), now.minusMinutes(2), maxAttempts, lease);
        database.jdbc().update("""
                INSERT INTO runtime_automation_attempt (id,occurrence_id,attempt_no,status,worker_id,lease_token,trace_id,started_at)
                VALUES (401,301,1,'RUNNING','old-worker','old-lease','old-trace',?)
                """, now.minusMinutes(2));
        database.jdbc().update("INSERT INTO runtime_automation_execution_slot (automation_id,slot_no,occurrence_id,lease_owner,lease_token,leased_until) VALUES (101,0,301,'old-worker','old-lease',?)", lease);
        database.jdbc().update("INSERT INTO runtime_run (trace_id,run_type,entry_type,status,started_at,ended_at) VALUES ('old-trace','WORKFLOW','AUTOMATION',?,?,?)",
                completedRun ? "COMPLETED" : "RUNNING", now.minusMinutes(2), completedRun ? now.minusMinutes(1) : null);
        database.jdbc().update("INSERT INTO runtime_trace_span (trace_id,span_id,span_type,status,started_at,ended_at) VALUES ('old-trace','old-span','WORKFLOW',?,?,?)",
                completedRun ? "SUCCESS" : "RUNNING", now.minusMinutes(2), completedRun ? now.minusMinutes(1) : null);
    }
}
