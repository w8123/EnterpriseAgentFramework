package com.enterprise.ai.runtime.automation;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RuntimeAutomationScheduleCalculatorTest {

    private final RuntimeAutomationScheduleCalculator calculator = new RuntimeAutomationScheduleCalculator();

    @Test
    void normalizesSpringCronAndUsesTheDeclaredIanaTimeZone() {
        RuntimeAutomationScheduleCalculator.NormalizedSchedule schedule = calculator.normalize(
                new RuntimeAutomationViews.ScheduleCommand(
                        "cron", "0 0 9 * * MON-FRI", null,
                        "Asia/Shanghai", "fire_once", 90, 5));

        assertEquals("CRON", schedule.type());
        assertEquals("0 0 9 * * MON-FRI", schedule.cronExpression());
        assertEquals("Asia/Shanghai", schedule.timeZone().getId());
        assertEquals("FIRE_ONCE", schedule.misfirePolicy());

        RuntimeAutomationVersionEntity version = version(
                "CRON", "0 0 9 * * *", null, "Asia/Shanghai", "FIRE_ONCE", 60, 10);
        assertEquals(
                Instant.parse("2026-01-01T01:00:00Z"),
                calculator.next(version, Instant.parse("2026-01-01T00:30:00Z")));
    }

    @Test
    void rejectsPastOneTimeSchedules() {
        RuntimeAutomationException failure = assertThrows(
                RuntimeAutomationException.class,
                () -> calculator.normalize(new RuntimeAutomationViews.ScheduleCommand(
                        "ONCE", null, "2020-01-01T00:00:00Z",
                        "UTC", "FIRE_ONCE", 60, 10)));

        assertEquals("AUTOMATION_FIRE_AT_PAST", failure.code());
    }

    @Test
    void appliesSkipFireOnceAndBoundedCatchUpWithoutChangingTheNominalInstant() {
        Instant nominal = Instant.parse("2026-01-01T00:00:00Z");
        Instant observed = Instant.parse("2026-01-01T00:10:00Z");

        RuntimeAutomationVersionEntity skip = version(
                "CRON", "0 * * * * *", null, "UTC", "SKIP", 0, 10);
        assertEquals(List.of(), calculator.dueInstants(skip, nominal, observed));

        RuntimeAutomationVersionEntity fireOnce = version(
                "CRON", "0 * * * * *", null, "UTC", "FIRE_ONCE", 0, 10);
        assertEquals(List.of(nominal), calculator.dueInstants(fireOnce, nominal, observed));

        RuntimeAutomationVersionEntity catchUp = version(
                "CRON", "0 * * * * *", null, "UTC", "CATCH_UP", 0, 3);
        assertEquals(List.of(
                        Instant.parse("2026-01-01T00:00:00Z"),
                        Instant.parse("2026-01-01T00:01:00Z"),
                        Instant.parse("2026-01-01T00:02:00Z")),
                calculator.dueInstants(catchUp, nominal, observed));
    }

    private RuntimeAutomationVersionEntity version(String triggerType,
                                                     String cron,
                                                     Instant fireAt,
                                                     String timeZone,
                                                     String misfire,
                                                     int grace,
                                                     int catchUp) {
        RuntimeAutomationVersionEntity version = new RuntimeAutomationVersionEntity();
        version.setTriggerType(triggerType);
        version.setCronExpression(cron);
        version.setFireAt(RuntimeAutomationScheduleCalculator.utc(fireAt));
        version.setTimeZone(timeZone);
        version.setMisfirePolicy(misfire);
        version.setMisfireGraceSeconds(grace);
        version.setMaxCatchUp(catchUp);
        return version;
    }
}
