package com.enterprise.ai.runtime.automation;

import com.github.kagkarlsson.scheduler.Scheduler;
import com.github.kagkarlsson.scheduler.SchedulerClient.ScheduleOptions;
import com.github.kagkarlsson.scheduler.task.TaskInstanceId;
import com.github.kagkarlsson.scheduler.task.helper.OneTimeTask;
import com.github.kagkarlsson.scheduler.task.helper.RecurringTaskWithPersistentSchedule;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;

import java.time.ZoneId;

final class RuntimeDbSchedulerAutomationEngine implements RuntimeAutomationEnginePort {

    private final Scheduler scheduler;
    private final RecurringTaskWithPersistentSchedule<RuntimeAutomationCronTaskData> cronTask;
    private final OneTimeTask<RuntimeAutomationOneTimeTaskData> oneTimeTask;

    RuntimeDbSchedulerAutomationEngine(
            Scheduler scheduler,
            RecurringTaskWithPersistentSchedule<RuntimeAutomationCronTaskData> cronTask,
            OneTimeTask<RuntimeAutomationOneTimeTaskData> oneTimeTask) {
        this.scheduler = scheduler;
        this.cronTask = cronTask;
        this.oneTimeTask = oneTimeTask;
    }

    @Override
    public void upsert(RuntimeAutomationEntity automation, RuntimeAutomationVersionEntity version) {
        if (automation == null || version == null || !"ACTIVE".equals(automation.getStatus())) return;
        String instanceId = automation.getAutomationKey();
        if ("CRON".equals(version.getTriggerType())) {
            cancelQuietly(RuntimeAutomationDbSchedulerConfiguration.ONCE_TASK, instanceId);
            RuntimeAutomationCronTaskData data = new RuntimeAutomationCronTaskData(
                    Schedules.cron(version.getCronExpression(), ZoneId.of(version.getTimeZone())),
                    automation.getId(), version.getId());
            scheduler.schedule(cronTask.schedulableInstance(instanceId, data),
                    ScheduleOptions.WHEN_EXISTS_RESCHEDULE);
        } else {
            cancelQuietly(RuntimeAutomationDbSchedulerConfiguration.CRON_TASK, instanceId);
            scheduler.schedule(
                    oneTimeTask.instance(instanceId,
                            new RuntimeAutomationOneTimeTaskData(automation.getId(), version.getId())),
                    RuntimeAutomationScheduleCalculator.instant(version.getFireAt()),
                    ScheduleOptions.WHEN_EXISTS_RESCHEDULE);
        }
    }

    @Override
    public void cancel(String automationKey) {
        cancelQuietly(RuntimeAutomationDbSchedulerConfiguration.CRON_TASK, automationKey);
        cancelQuietly(RuntimeAutomationDbSchedulerConfiguration.ONCE_TASK, automationKey);
    }

    @Override
    public String engineName() {
        return "db-scheduler";
    }

    private void cancelQuietly(String taskName, String instanceId) {
        try {
            scheduler.cancel(TaskInstanceId.of(taskName, instanceId));
        } catch (RuntimeException missingOrRacing) {
            // Cancel is idempotent at the Automation command boundary; a missing engine row is already cancelled.
        }
    }
}
