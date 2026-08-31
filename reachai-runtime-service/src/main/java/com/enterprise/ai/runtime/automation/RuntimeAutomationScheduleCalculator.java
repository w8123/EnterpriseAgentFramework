package com.enterprise.ai.runtime.automation;

import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

@Component
final class RuntimeAutomationScheduleCalculator {

    NormalizedSchedule normalize(RuntimeAutomationViews.ScheduleCommand command) {
        if (command == null) {
            throw RuntimeAutomationException.badRequest(
                    "AUTOMATION_SCHEDULE_REQUIRED", "schedule is required");
        }
        String type = RuntimeAutomationTypes.upper(command.type());
        if (!RuntimeAutomationTypes.TRIGGER_TYPES.contains(type)) {
            throw RuntimeAutomationException.badRequest(
                    "AUTOMATION_TRIGGER_INVALID", "schedule.type must be CRON or ONCE");
        }
        ZoneId zone = zone(command.timeZone());
        String misfire = defaultText(RuntimeAutomationTypes.upper(command.misfirePolicy()), "FIRE_ONCE");
        if (!RuntimeAutomationTypes.MISFIRE_POLICIES.contains(misfire)) {
            throw RuntimeAutomationException.badRequest(
                    "AUTOMATION_MISFIRE_INVALID", "unsupported misfire policy: " + misfire);
        }
        int grace = bounded(command.misfireGraceSeconds(), 60, 0, 86_400, "misfireGraceSeconds");
        int maxCatchUp = bounded(command.maxCatchUp(), 10, 1, 100, "maxCatchUp");
        if ("CRON".equals(type)) {
            if (!StringUtils.hasText(command.cronExpression())) {
                throw RuntimeAutomationException.badRequest(
                        "AUTOMATION_CRON_REQUIRED", "cronExpression is required for CRON schedules");
            }
            String cron = command.cronExpression().trim();
            try {
                CronExpression.parse(cron);
            } catch (IllegalArgumentException invalid) {
                throw RuntimeAutomationException.badRequest(
                        "AUTOMATION_CRON_INVALID", "invalid Spring-style cron expression");
            }
            return new NormalizedSchedule(type, cron, null, zone, misfire, grace, maxCatchUp);
        }
        Instant fireAt;
        try {
            fireAt = Instant.parse(requireText(command.fireAt(), "fireAt"));
        } catch (DateTimeException invalid) {
            throw RuntimeAutomationException.badRequest(
                    "AUTOMATION_FIRE_AT_INVALID", "fireAt must be an ISO-8601 UTC instant");
        }
        if (!fireAt.isAfter(Instant.now())) {
            throw RuntimeAutomationException.badRequest(
                    "AUTOMATION_FIRE_AT_PAST", "fireAt must be in the future");
        }
        return new NormalizedSchedule(type, null, fireAt, zone, misfire, grace, maxCatchUp);
    }

    Instant next(RuntimeAutomationVersionEntity version, Instant after) {
        if (version == null) return null;
        if ("ONCE".equals(version.getTriggerType())) {
            Instant fire = instant(version.getFireAt());
            return fire != null && fire.isAfter(after) ? fire : null;
        }
        CronExpression cron = CronExpression.parse(version.getCronExpression());
        ZonedDateTime next = cron.next(after.atZone(zone(version.getTimeZone())));
        return next == null ? null : next.toInstant();
    }

    List<Instant> dueInstants(RuntimeAutomationVersionEntity version,
                              Instant nominal,
                              Instant observedAt) {
        if (nominal == null) return List.of();
        long lateness = Math.max(0, observedAt.getEpochSecond() - nominal.getEpochSecond());
        int grace = value(version.getMisfireGraceSeconds(), 60);
        String policy = defaultText(version.getMisfirePolicy(), "FIRE_ONCE");
        if (lateness <= grace) return List.of(nominal);
        if ("SKIP".equals(policy)) return List.of();
        if (!"CATCH_UP".equals(policy) || !"CRON".equals(version.getTriggerType())) {
            return List.of(nominal);
        }
        int limit = Math.max(1, Math.min(100, value(version.getMaxCatchUp(), 10)));
        List<Instant> due = new ArrayList<>();
        Instant cursor = nominal;
        while (cursor != null && !cursor.isAfter(observedAt) && due.size() < limit) {
            due.add(cursor);
            cursor = next(version, cursor);
        }
        return List.copyOf(due);
    }

    static LocalDateTime utc(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    static Instant instant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    static String instantText(LocalDateTime value) {
        Instant instant = instant(value);
        return instant == null ? null : instant.toString();
    }

    private ZoneId zone(String value) {
        try {
            return ZoneId.of(StringUtils.hasText(value) ? value.trim() : "Asia/Shanghai");
        } catch (DateTimeException invalid) {
            throw RuntimeAutomationException.badRequest(
                    "AUTOMATION_TIME_ZONE_INVALID", "unknown IANA time zone: " + value);
        }
    }

    private int bounded(Integer raw, int fallback, int min, int max, String field) {
        int value = raw == null ? fallback : raw;
        if (value < min || value > max) {
            throw RuntimeAutomationException.badRequest(
                    "AUTOMATION_POLICY_INVALID", field + " must be between " + min + " and " + max);
        }
        return value;
    }

    private int value(Integer raw, int fallback) {
        return raw == null ? fallback : raw;
    }

    private String requireText(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw RuntimeAutomationException.badRequest(
                    "AUTOMATION_SCHEDULE_INVALID", field + " is required");
        }
        return value.trim();
    }

    private String defaultText(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    record NormalizedSchedule(
            String type,
            String cronExpression,
            Instant fireAt,
            ZoneId timeZone,
            String misfirePolicy,
            int misfireGraceSeconds,
            int maxCatchUp) {
    }
}
