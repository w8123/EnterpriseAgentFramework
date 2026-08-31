package com.enterprise.ai.runtime.automation;

import com.github.kagkarlsson.scheduler.Scheduler;
import com.github.kagkarlsson.scheduler.task.helper.OneTimeTask;
import com.github.kagkarlsson.scheduler.task.helper.RecurringTaskWithPersistentSchedule;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.time.Duration;

@Configuration
@ConditionalOnProperty(name = "reachai.runtime.automation.enabled", havingValue = "true")
class RuntimeAutomationDbSchedulerConfiguration {

    static final String CRON_TASK = "reachai-automation-cron";
    static final String ONCE_TASK = "reachai-automation-once";

    @Bean
    RecurringTaskWithPersistentSchedule<RuntimeAutomationCronTaskData> runtimeAutomationCronTask(
            RuntimeAutomationMaterializer materializer) {
        return Tasks.recurringWithPersistentSchedule(CRON_TASK, RuntimeAutomationCronTaskData.class)
                .execute((instance, context) -> materializer.materialize(
                        instance.getData().automationId(),
                        instance.getData().automationVersionId(),
                        context.getExecution().getExecutionTime()));
    }

    @Bean
    OneTimeTask<RuntimeAutomationOneTimeTaskData> runtimeAutomationOneTimeTask(
            RuntimeAutomationMaterializer materializer) {
        return Tasks.oneTime(ONCE_TASK, RuntimeAutomationOneTimeTaskData.class)
                .execute((instance, context) -> materializer.materialize(
                        instance.getData().automationId(),
                        instance.getData().automationVersionId(),
                        context.getExecution().getExecutionTime()));
    }

    @Bean(initMethod = "start", destroyMethod = "stop")
    Scheduler runtimeAutomationDbScheduler(
            DataSource dataSource,
            RecurringTaskWithPersistentSchedule<RuntimeAutomationCronTaskData> cronTask,
            OneTimeTask<RuntimeAutomationOneTimeTaskData> oneTimeTask,
            @Value("${reachai.runtime.automation.clock-threads:2}") int threads,
            @Value("${reachai.runtime.automation.clock-polling-interval-ms:1000}") long pollingMs) {
        return Scheduler.create(dataSource, cronTask, oneTimeTask)
                .tableName("runtime_scheduler_task")
                .threads(Math.max(1, Math.min(8, threads)))
                .pollingInterval(Duration.ofMillis(Math.max(250, pollingMs)))
                .pollUsingFetch(0.5, 3.0)
                .alwaysPersistTimestampInUTC()
                .enableImmediateExecution()
                .shutdownMaxWait(Duration.ofSeconds(30))
                .build();
    }

    @Bean
    RuntimeAutomationEnginePort runtimeAutomationEnginePort(
            Scheduler runtimeAutomationDbScheduler,
            RecurringTaskWithPersistentSchedule<RuntimeAutomationCronTaskData> cronTask,
            OneTimeTask<RuntimeAutomationOneTimeTaskData> oneTimeTask) {
        return new RuntimeDbSchedulerAutomationEngine(runtimeAutomationDbScheduler, cronTask, oneTimeTask);
    }
}
