package com.enterprise.ai.control.activity;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 主动感知: receives what business systems report, keeps per-system settings, answers the manager view. */
@Slf4j
@Service
public class ActivityService {

    static final int MAX_EVENTS_PER_BATCH = 500;
    static final int DEFAULT_RETENTION_DAYS = 90;

    private final ActivityEventMapper events;
    private final ActivitySettingMapper settings;

    public ActivityService(ActivityEventMapper events, ActivitySettingMapper settings) {
        this.events = events;
        this.settings = settings;
    }

    // ------------------------------------------------------------------ ingest

    @Transactional
    public IngestResult ingest(String projectCode, JsonNode body) {
        Setting setting = setting(projectCode);
        if (!setting.enabled()) {
            return new IngestResult(0, 0, false, setting.captureSearch());
        }
        JsonNode list = body == null ? null : body.path("events");
        if (list == null || !list.isArray()) {
            return new IngestResult(0, 0, true, setting.captureSearch());
        }
        String source = ActivitySanitizer.plain(body.path("sdk").asText(null), 32);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime oldest = now.minusDays(setting.retentionDays());
        int accepted = 0;
        int dropped = 0;
        int index = 0;
        for (JsonNode item : list) {
            if (++index > MAX_EVENTS_PER_BATCH) {
                dropped++;
                continue;
            }
            ActivityEventEntity event = toEvent(projectCode, item, setting, source, now, oldest);
            if (event == null) {
                dropped++;
                continue;
            }
            events.insert(event);
            accepted++;
        }
        return new IngestResult(accepted, dropped, true, setting.captureSearch());
    }

    ActivityEventEntity toEvent(String projectCode, JsonNode item, Setting setting, String source,
                                LocalDateTime now, LocalDateTime oldest) {
        if (item == null || !item.isObject()) return null;
        String rawUser = ActivitySanitizer.plain(item.path("userId").asText(null), 128);
        if (rawUser == null) return null;
        String tenant = ActivitySanitizer.plain(item.path("tenantId").asText(null), 96);
        String keyword = setting.captureSearch() ? ActivitySanitizer.text(item.path("keyword").asText(null), 64) : null;
        String operation = ActivitySanitizer.operationKey(item.path("operation").asText(null));
        String type = "SEARCH".equalsIgnoreCase(item.path("type").asText("")) ? "SEARCH" : "OPERATION";
        if (operation == null) {
            if (keyword == null) return null;
            operation = "SEARCH";
        }
        if ("SEARCH".equals(type) && keyword == null) type = "OPERATION";
        ActivityEventEntity event = new ActivityEventEntity();
        event.setProjectCode(projectCode);
        event.setTenantId(tenant);
        if (setting.hideUserNames()) {
            event.setUserId("员工-" + sha256(projectCode + "\n" + (tenant == null ? "" : tenant) + "\n" + rawUser)
                    .substring(0, 10));
        } else {
            event.setUserId(rawUser);
            event.setUserName(ActivitySanitizer.plain(item.path("userName").asText(null), 128));
        }
        event.setEventType(type);
        event.setOperationKey(operation);
        event.setOperationName(ActivitySanitizer.text(item.path("operationName").asText(null), 200));
        event.setPagePath(ActivitySanitizer.path(item.path("page").asText(null)));
        event.setKeyword(keyword);
        String outcome = item.path("outcome").asText("SUCCESS").trim().toUpperCase(Locale.ROOT);
        boolean failed = "FAILED".equals(outcome) || "FAILURE".equals(outcome) || "ERROR".equals(outcome);
        event.setOutcome(failed ? "FAILED" : "SUCCESS");
        event.setErrorMessage(failed ? ActivitySanitizer.text(item.path("error").asText(null), 300) : null);
        if (item.path("durationMs").canConvertToInt()) {
            int duration = item.path("durationMs").asInt();
            event.setDurationMs(duration < 0 ? null : Math.min(duration, 86_400_000));
        }
        event.setSource(source);
        LocalDateTime occurred = now;
        if (item.path("occurredAt").canConvertToLong()) {
            occurred = LocalDateTime.ofInstant(Instant.ofEpochMilli(item.path("occurredAt").asLong()),
                    ZoneId.systemDefault());
        }
        if (occurred.isAfter(now.plusMinutes(5))) occurred = now;
        if (occurred.isBefore(oldest)) return null;
        event.setOccurredAt(occurred);
        return event;
    }

    // ------------------------------------------------------------------ settings

    public Setting setting(String projectCode) {
        ActivitySettingEntity row = settings.selectById(projectCode);
        if (row == null) {
            return new Setting(projectCode, true, true, false, DEFAULT_RETENTION_DAYS, null, null, false);
        }
        return new Setting(projectCode,
                !Boolean.FALSE.equals(row.getEnabled()),
                !Boolean.FALSE.equals(row.getCaptureSearch()),
                Boolean.TRUE.equals(row.getHideUserNames()),
                retention(row.getRetentionDays()),
                row.getUpdatedBy(),
                row.getUpdatedAt() == null ? null : row.getUpdatedAt().toString(),
                true);
    }

    @Transactional
    public Setting updateSetting(String projectCode, SettingCommand command, String actor) {
        if (command == null) throw new IllegalArgumentException("缺少设置内容");
        Setting current = setting(projectCode);
        ActivitySettingEntity row = new ActivitySettingEntity();
        row.setProjectCode(projectCode);
        row.setEnabled(command.enabled() == null ? current.enabled() : command.enabled());
        row.setCaptureSearch(command.captureSearch() == null ? current.captureSearch() : command.captureSearch());
        row.setHideUserNames(command.hideUserNames() == null ? current.hideUserNames() : command.hideUserNames());
        row.setRetentionDays(command.retentionDays() == null ? current.retentionDays() : retention(command.retentionDays()));
        row.setUpdatedBy(actor);
        row.setUpdatedAt(LocalDateTime.now());
        if (current.saved()) {
            settings.updateById(row);
        } else {
            settings.insert(row);
        }
        return setting(projectCode);
    }

    private static int retention(Integer days) {
        if (days == null) return DEFAULT_RETENTION_DAYS;
        return Math.max(7, Math.min(365, days));
    }

    // ------------------------------------------------------------------ report

    public Report report(Integer requestedDays, String projectCode) {
        int days = requestedDays == null ? 7 : Math.max(1, Math.min(90, requestedDays));
        String project = StringUtils.hasText(projectCode) ? projectCode.trim() : null;
        LocalDateTime since = LocalDate.now().minusDays(days - 1L).atStartOfDay();
        Map<String, Object> summary = normalize(events.summary(project, since));
        return new Report(days, project, summary,
                normalizeAll(events.topOperations(project, since, 30)),
                normalizeAll(events.topKeywords(project, since, 20)),
                normalizeAll(events.problems(project, since, 20)),
                normalizeAll(events.people(project, since, 30)),
                normalizeAll(events.byDay(project, since)),
                project == null ? null : setting(project));
    }

    private static List<Map<String, Object>> normalizeAll(List<Map<String, Object>> rows) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (rows != null) for (Map<String, Object> row : rows) result.add(normalize(row));
        return result;
    }

    private static Map<String, Object> normalize(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (row == null) return result;
        row.forEach((key, value) -> {
            if (value instanceof java.math.BigDecimal decimal) {
                result.put(key, decimal.longValue());
            } else if (value instanceof Number number) {
                result.put(key, number.longValue());
            } else if (value instanceof java.sql.Timestamp timestamp) {
                result.put(key, timestamp.toLocalDateTime().truncatedTo(ChronoUnit.SECONDS).toString());
            } else if (value instanceof java.sql.Date date) {
                result.put(key, date.toLocalDate().toString());
            } else if (value instanceof LocalDateTime time) {
                result.put(key, time.truncatedTo(ChronoUnit.SECONDS).toString());
            } else {
                result.put(key, value);
            }
        });
        return result;
    }

    // ------------------------------------------------------------------ retention

    @Scheduled(cron = "${reachai.activity.retention-cron:0 40 3 * * *}")
    public void purgeExpired() {
        try {
            for (ActivitySettingEntity row : settings.selectList(null)) {
                LocalDateTime before = LocalDateTime.now().minusDays(retention(row.getRetentionDays()));
                for (int round = 0; round < 100 && events.deleteProjectBefore(row.getProjectCode(), before, 5000) > 0; round++) {
                    // keep deleting in small chunks so the table is never locked for long
                }
            }
            LocalDateTime before = LocalDateTime.now().minusDays(DEFAULT_RETENTION_DAYS);
            for (int round = 0; round < 100 && events.deleteUnconfiguredBefore(before, 5000) > 0; round++) {
                // same as above for systems that kept the default retention
            }
        } catch (Exception failure) {
            log.warn("[Activity] retention purge failed: {}", failure.toString());
        }
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    public record IngestResult(int accepted, int dropped, boolean enabled, boolean captureSearch) {
    }

    public record Setting(String projectCode, boolean enabled, boolean captureSearch, boolean hideUserNames,
                          int retentionDays, String updatedBy, String updatedAt, boolean saved) {
    }

    public record SettingCommand(Boolean enabled, Boolean captureSearch, Boolean hideUserNames, Integer retentionDays) {
    }

    public record Report(int days, String projectCode, Map<String, Object> summary,
                         List<Map<String, Object>> operations, List<Map<String, Object>> keywords,
                         List<Map<String, Object>> problems, List<Map<String, Object>> people,
                         List<Map<String, Object>> byDay, Setting setting) {
    }
}
