package com.enterprise.ai.runtime.automation;

import com.enterprise.ai.common.testing.ClonedDevelopmentMysqlDatabase;
import com.enterprise.ai.runtime.support.RuntimeQueryTestDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class RuntimeAutomationUtcPersistenceTest {
    private static final List<String> TABLES = List.of("runtime_automation", "runtime_automation_version",
            "runtime_automation_occurrence", "runtime_automation_execution_slot", "runtime_automation_engine_command");
    private static final boolean MYSQL = Boolean.getBoolean("reachai.mysql.automationClockVerification");
    private ClonedDevelopmentMysqlDatabase mysql;
    private RuntimeQueryTestDatabase database;
    private RuntimeAutomationMapper automations;
    private RuntimeAutomationOccurrenceMapper occurrences;
    private RuntimeAutomationExecutionSlotMapper slots;
    private RuntimeAutomationEngineCommandMapper commands;

    @BeforeEach
    void open() throws Exception {
        Class<?>[] mappers = {RuntimeAutomationOccurrenceMapper.class, RuntimeAutomationExecutionSlotMapper.class,
                RuntimeAutomationEngineCommandMapper.class, RuntimeAutomationMapper.class};
        if (MYSQL) {
            mysql = new ClonedDevelopmentMysqlDatabase("reachai.mysql.automationClockVerification",
                    "audit_automation_clock", "runtime_", TABLES);
            database = new RuntimeQueryTestDatabase(mysql, mappers);
        } else {
            database = new RuntimeQueryTestDatabase(TABLES, mappers);
            database.jdbc().execute("CREATE ALIAS IF NOT EXISTS UTC_TIMESTAMP FOR \""
                    + RuntimeAutomationUtcPersistenceTest.class.getName() + ".utcTimestamp\"");
        }
        automations = database.mapper(RuntimeAutomationMapper.class);
        occurrences = database.mapper(RuntimeAutomationOccurrenceMapper.class);
        slots = database.mapper(RuntimeAutomationExecutionSlotMapper.class);
        commands = database.mapper(RuntimeAutomationEngineCommandMapper.class);
    }

    // H2's alias supplies MySQL's UTC wall-clock function; MySQL runs the original mapper SQL unchanged.
    public static LocalDateTime utcTimestamp(int precision) { return LocalDateTime.now(Clock.systemUTC()); }

    @AfterEach
    void close() {
        try { if (database != null) database.close(); }
        finally { if (mysql != null) mysql.close(); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"+08:00", "-05:00"})
    void freshOccurrenceCanRunAndFinishWithUtcLeasesAndAuditTimes(String zone) {
        inZone(zone, () -> {
            LocalDateTime now = utcTimestamp(6);
            seed(now.minusMinutes(1), now);
            assertEquals(301L, occurrences.findLeaseCandidateId(), "Due UTC work must be eligible in either session zone");
            assertEquals(1, occurrences.claim(301L, "worker", "occurrence-token", now.plusMinutes(10)));
            slots.tryAcquire(101L, 0, 301L, "worker", "occurrence-token", now.plusMinutes(10));
            assertEquals(1, slots.owns(101L, 0, 301L, "occurrence-token"), "A fresh UTC slot must not expire immediately");
            slots.tryAcquire(101L, 0, 302L, "other-worker", "intruder", now.plusMinutes(20));
            assertEquals(1, slots.owns(101L, 0, 301L, "occurrence-token"), "An unexpired slot must not be stolen");
            assertEquals(0, slots.owns(101L, 0, 302L, "intruder"));
            assertEquals(0, occurrences.markExpiredExhausted(), "The final attempt still owns a valid lease");
            assertEquals(1, occurrences.markRunning(301L, "occurrence-token", "automation-utc-trace"));
            assertEquals(1, occurrences.renew(301L, "occurrence-token", now.plusMinutes(11)));
            assertEquals(1, slots.renew(101L, 0, 301L, "occurrence-token", now.plusMinutes(11)));
            assertUtc(database.jdbc().queryForObject("SELECT updated_at FROM runtime_automation_execution_slot WHERE automation_id=101", LocalDateTime.class));
            assertEquals(401L, commands.findCandidateId());
            assertEquals(1, commands.claim(401L, "clock", "command-token", now.plusMinutes(2)));
            assertNull(commands.findCandidateId(), "A current clock-command lease must not be stolen");
            assertEquals(1, commands.complete(401L, "command-token"));
            assertEquals(1, occurrences.complete(301L, "occurrence-token"));
            assertEquals("SUCCEEDED", occurrences.selectById(301L).getStatus());
            assertUtc(occurrences.selectById(301L).getCompletedAt());
            assertUtc(commands.selectById(401L).getCompletedAt());
            assertEquals(1, slots.release(101L, 0, 301L, "occurrence-token"));
            assertEquals(1, automations.updateVersion(101L, 1L, "UTC verification", null, null, null,
                    201L, "ACTIVE", now.plusHours(1), "test"));
            assertUtc(automations.selectById(101L).getUpdatedAt());
            assertEquals(1, automations.transition(101L, 2L, "PAUSED", null, "test"));
            assertUtc(automations.selectById(101L).getUpdatedAt());
            assertEquals(1, automations.transition(101L, 3L, "ACTIVE", now.plusHours(1), "test"));
            assertEquals(1, automations.updateFireTimes(101L, now.plusHours(1), now));
            assertUtc(automations.selectById(101L).getUpdatedAt());
            assertEquals(1, automations.completeOnce(101L, 201L, now));
            assertEquals("COMPLETED", automations.selectById(101L).getStatus());
            assertUtc(automations.selectById(101L).getUpdatedAt());
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"+08:00", "-05:00"})
    void futurePollingAndExpiredLeaseRecoveryUseUtc(String zone) {
        inZone(zone, () -> {
            LocalDateTime now = utcTimestamp(6);
            seed(now.plusHours(1), now);
            assertNull(occurrences.findLeaseCandidateId(), "Future UTC work must not execute early");
            assertEquals(0, occurrences.claim(301L, "worker", "early", now.plusMinutes(10)));
            assertNull(commands.findCandidateId(), "Future UTC clock commands must remain pending");
            assertEquals(0, commands.claim(401L, "clock", "early", now.plusMinutes(2)));
            database.jdbc().update("UPDATE runtime_automation_occurrence SET status='RUNNING',leased_until=?,attempt_count=0,max_attempts=2 WHERE id=301", now.minusMinutes(1));
            database.jdbc().update("UPDATE runtime_automation_engine_command SET status='PROCESSING',leased_until=? WHERE id=401", now.minusMinutes(1));
            assertEquals(301L, occurrences.findLeaseCandidateId(), "Expired UTC work must be reclaimable");
            assertEquals(401L, commands.findCandidateId(), "Expired UTC commands must be reclaimable");
            assertEquals(1, occurrences.claim(301L, "worker", "reclaimed", now.plusMinutes(10)));
            assertEquals(1, commands.claim(401L, "clock", "reclaimed", now.plusMinutes(2)));
            database.jdbc().update("""
                    INSERT INTO runtime_automation_execution_slot
                      (automation_id,slot_no,occurrence_id,lease_owner,lease_token,leased_until,updated_at)
                    VALUES (101,0,301,'previous','expired',?,?)
                    """, now.minusMinutes(1), now.minusHours(1));
            slots.tryAcquire(101L, 0, 301L, "worker", "reclaimed", now.plusMinutes(10));
            assertEquals(1, slots.owns(101L, 0, 301L, "reclaimed"));
            assertUtc(database.jdbc().queryForObject("SELECT updated_at FROM runtime_automation_execution_slot WHERE automation_id=101", LocalDateTime.class));
            assertEquals(0, slots.owns(101L, 0, 301L, "expired"));
        });
    }

    private void inZone(String zone, Runnable assertions) {
        new TransactionTemplate(new DataSourceTransactionManager(database.jdbc().getDataSource())).executeWithoutResult(status -> {
            database.jdbc().execute((ConnectionCallback<Void>) connection -> {
                // Only this fixed session setting bypasses the clone's owning-table SQL rewrite.
                Connection physical = MYSQL ? connection.unwrap(com.mysql.cj.jdbc.JdbcConnection.class)
                        : connection.unwrap(Connection.class);
                try (var statement = physical.createStatement()) {
                    statement.execute((MYSQL ? "SET time_zone=" : "SET TIME ZONE ") + "'" + zone + "'");
                }
                return null;
            });
            assertions.run();
            status.setRollbackOnly();
        });
    }

    private void seed(LocalDateTime available, LocalDateTime now) {
        database.jdbc().update("""
                INSERT INTO runtime_automation
                  (id,automation_key,name,status,created_by,updated_by,created_at,updated_at)
                VALUES (101,'aut_utc_test','UTC verification','ACTIVE','test','test',?,?)
                """, now, now);
        database.jdbc().update("""
                INSERT INTO runtime_automation_version
                  (id,automation_id,version_no,target_type,target_id,target_version_id,target_snapshot_json,
                   trigger_type,fire_at,time_zone,misfire_policy,concurrency_policy,input_json,
                   principal_type,principal_id,principal_snapshot_json,fingerprint_sha256,created_by,created_at)
                VALUES (201,101,1,'WORKFLOW','wf-utc',44,'{}','ONCE',?,'Asia/Shanghai','SKIP','QUEUE','{}',
                        'AUTOMATION_SERVICE_ACCOUNT','utc-principal','{}',?,'test',?)
                """, available, "a".repeat(64), now);
        database.jdbc().update("""
                INSERT INTO runtime_automation_occurrence
                  (id,occurrence_key,automation_id,automation_version_id,source_type,scheduled_at,available_at,
                   status,max_attempts,input_snapshot_json,principal_snapshot_json,created_at,updated_at)
                VALUES (301,'utc-occurrence',101,201,'MANUAL',?,?,'PENDING',1,'{}','{}',?,?)
                """, available, available, now, now);
        database.jdbc().update("""
                INSERT INTO runtime_automation_engine_command
                  (id,automation_id,automation_version_id,command_type,status,available_at,created_at,updated_at)
                VALUES (401,101,201,'UPSERT','PENDING',?,?,?)
                """, available, now, now);
    }

    private void assertUtc(LocalDateTime actual) {
        assertNotNull(actual);
        assertTrue(Duration.between(utcTimestamp(6), actual).abs().compareTo(Duration.ofSeconds(30)) < 0,
                "Automation audit timestamp must remain UTC: " + actual);
    }
}
