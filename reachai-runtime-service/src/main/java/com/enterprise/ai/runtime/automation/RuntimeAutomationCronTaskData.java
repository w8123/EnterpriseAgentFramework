package com.enterprise.ai.runtime.automation;

import com.github.kagkarlsson.scheduler.task.helper.ScheduleAndData;
import com.github.kagkarlsson.scheduler.task.schedule.Schedule;

import java.io.Serial;
import java.io.Serializable;

final class RuntimeAutomationCronTaskData implements ScheduleAndData, Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private final Schedule schedule;
    private final Long automationId;
    private final Long automationVersionId;

    RuntimeAutomationCronTaskData(Schedule schedule, Long automationId, Long automationVersionId) {
        this.schedule = schedule;
        this.automationId = automationId;
        this.automationVersionId = automationVersionId;
    }

    @Override
    public Schedule getSchedule() {
        return schedule;
    }

    @Override
    public Object getData() {
        return automationId;
    }

    Long automationId() {
        return automationId;
    }

    Long automationVersionId() {
        return automationVersionId;
    }
}
