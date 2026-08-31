package com.enterprise.ai.runtime.automation;

import com.github.kagkarlsson.scheduler.Scheduler;
import com.github.kagkarlsson.scheduler.task.helper.OneTimeTask;
import com.github.kagkarlsson.scheduler.task.helper.RecurringTaskWithPersistentSchedule;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuntimeAutomationClusterClockTest {

    @Test
    void twoSchedulerReplicasMaterializeOneNominalOccurrence() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:automation_cluster;MODE=MySQL;DB_CLOSE_DELAY=-1");
        createSchedulerTable(dataSource);

        RuntimeAutomationMapper automationMapper = mock(RuntimeAutomationMapper.class);
        RuntimeAutomationVersionMapper versionMapper = mock(RuntimeAutomationVersionMapper.class);
        RuntimeAutomationOccurrenceMapper occurrenceMapper = mock(RuntimeAutomationOccurrenceMapper.class);
        RuntimeAutomationEventMapper eventMapper = mock(RuntimeAutomationEventMapper.class);
        RuntimeAutomationEntity automation = new RuntimeAutomationEntity();
        automation.setId(1L);
        automation.setAutomationKey("aut_0123456789abcdef0123456789abcdef");
        automation.setStatus("ACTIVE");
        automation.setCurrentVersionId(2L);
        RuntimeAutomationVersionEntity version = new RuntimeAutomationVersionEntity();
        version.setId(2L);
        version.setAutomationId(1L);
        version.setTriggerType("ONCE");
        version.setMisfirePolicy("FIRE_ONCE");
        version.setMisfireGraceSeconds(60);
        version.setMaxAttempts(1);
        version.setInputJson("{}");
        version.setPrincipalSnapshotJson("{}");
        when(automationMapper.selectById(1L)).thenReturn(automation);
        when(versionMapper.selectById(2L)).thenReturn(version);
        AtomicInteger materialized = new AtomicInteger();
        CountDownLatch firstOccurrence = new CountDownLatch(1);
        doAnswer(invocation -> {
            materialized.incrementAndGet();
            firstOccurrence.countDown();
            return 1;
        }).when(occurrenceMapper).insert(any(RuntimeAutomationOccurrenceEntity.class));

        RuntimeAutomationMaterializer materializer = new RuntimeAutomationMaterializer(
                automationMapper,
                versionMapper,
                occurrenceMapper,
                eventMapper,
                new RuntimeAutomationScheduleCalculator());
        RuntimeAutomationDbSchedulerConfiguration configuration =
                new RuntimeAutomationDbSchedulerConfiguration();
        RecurringTaskWithPersistentSchedule<RuntimeAutomationCronTaskData> cronTask =
                configuration.runtimeAutomationCronTask(materializer);
        OneTimeTask<RuntimeAutomationOneTimeTaskData> oneTimeTask =
                configuration.runtimeAutomationOneTimeTask(materializer);
        Scheduler first = configuration.runtimeAutomationDbScheduler(
                dataSource, cronTask, oneTimeTask, 1, 50);
        Scheduler second = configuration.runtimeAutomationDbScheduler(
                dataSource, cronTask, oneTimeTask, 1, 50);

        try {
            first.start();
            second.start();
            first.schedule(
                    oneTimeTask.instance(
                            automation.getAutomationKey(),
                            new RuntimeAutomationOneTimeTaskData(1L, 2L)),
                    Instant.now().plusMillis(250));

            assertTrue(firstOccurrence.await(5, TimeUnit.SECONDS),
                    "one scheduler replica should materialize the occurrence");
            Thread.sleep(350);
            assertEquals(1, materialized.get(),
                    "the same persisted task must not execute once per Runtime replica");
        } finally {
            first.stop();
            second.stop();
        }
    }

    private void createSchedulerTable(JdbcDataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE runtime_scheduler_task (
                        task_name VARCHAR(100) NOT NULL,
                        task_instance VARCHAR(100) NOT NULL,
                        task_data BLOB,
                        execution_time TIMESTAMP(6) NOT NULL,
                        picked BOOLEAN NOT NULL,
                        picked_by VARCHAR(50),
                        last_success TIMESTAMP(6),
                        last_failure TIMESTAMP(6),
                        consecutive_failures INT,
                        last_heartbeat TIMESTAMP(6),
                        version BIGINT NOT NULL,
                        priority SMALLINT,
                        PRIMARY KEY (task_name, task_instance)
                    )
                    """);
            statement.execute("CREATE INDEX idx_runtime_scheduler_execution_time "
                    + "ON runtime_scheduler_task(execution_time)");
        }
    }
}
