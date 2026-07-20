package com.enterprise.ai.control.aiassist;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Durable SDK access progress shared by the platform-session and AI Coding routes.
 */
@Service
@RequiredArgsConstructor
public class ControlAiAccessSessionService {

    private static final String SCENARIO = "SDK_ACCESS";
    private static final List<StepDefinition> SDK_STEPS = List.of(
            new StepDefinition("PROJECT", "项目识别"),
            new StepDefinition("STARTER", "后端 Starter"),
            new StepDefinition("GATEWAY", "网关路由"),
            new StepDefinition("BUSINESS_API", "业务服务校验"),
            new StepDefinition("EMBED_TOKEN", "前端 Embed Token"),
            new StepDefinition("FINAL_CHECK", "最终自检")
    );
    private static final Set<String> ALLOWED_STATUSES = Set.of(
            "TODO", "RUNNING", "PASS", "WARN", "FAIL", "SKIPPED");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Transactional
    public ControlAiAssistProjectController.AiAccessSessionView openSdkSession(
            Long projectId,
            String projectCode,
            String toolName,
            String requestedSessionId) {
        requireProjectId(projectId);
        String sessionId = StringUtils.hasText(requestedSessionId)
                ? requestedSessionId.trim()
                : "sdk-access-" + projectId;
        ensureSession(projectId, projectCode, toolName, sessionId);
        ensureCanonicalSteps(projectId, sessionId);
        return requireView(projectId, sessionId);
    }

    @Transactional
    public ControlAiAssistProjectController.AiAccessSessionView reportSdkStep(
            Long projectId,
            String projectCode,
            String sessionId,
            String stepKey,
            Map<String, ?> report,
            String defaultReporter) {
        openSdkSession(projectId, projectCode, null, sessionId);
        String canonicalKey = canonicalStepKey(stepKey);
        String status = normalizeStatus(text(report == null ? null : report.get("status")), "PASS");
        String message = text(report == null ? null : report.get("message"));
        List<String> files = stringList(report == null ? null : report.get("files"));
        Map<String, Object> evidence = objectMap(report == null ? null : report.get("evidence"));
        String reportedBy = firstText(
                text(report == null ? null : report.get("reportedBy")),
                defaultReporter,
                "ai-coding");
        updateStep(projectId, sessionId, canonicalKey, status, message, files, evidence, reportedBy, false);
        recalculateSession(sessionId, message);
        return requireView(projectId, sessionId);
    }

    @Transactional
    public ControlAiAssistProjectController.AiAccessSessionView applySdkCheckResult(
            Long projectId,
            String projectCode,
            String sessionId,
            ControlAiAssistProjectController.SdkAccessCheckResponse checkResult) {
        openSdkSession(projectId, projectCode, null, sessionId);
        Map<String, ControlAiAssistProjectController.SdkAccessReadiness> readiness = new LinkedHashMap<>();
        if (checkResult != null && checkResult.readiness() != null) {
            for (ControlAiAssistProjectController.SdkAccessReadiness item : checkResult.readiness()) {
                if (item != null && StringUtils.hasText(item.key())) readiness.put(item.key(), item);
            }
        }

        updateFromReadiness(projectId, sessionId, "PROJECT", "PASS",
                "Project manifest loaded by platform self-check", Map.of("source", "platform-self-check"));
        applyReadiness(projectId, sessionId, "STARTER", readiness.get("CODE_READY"));
        applyReadiness(projectId, sessionId, "BUSINESS_API", readiness.get("RUNTIME_READY"));

        ControlAiAssistProjectController.SdkAccessReadiness e2e = readiness.get("E2E_READY");
        if (e2e != null && "PASS".equals(normalizeReadinessStatus(e2e.status()))) {
            applyReadiness(projectId, sessionId, "GATEWAY", e2e);
            applyReadiness(projectId, sessionId, "EMBED_TOKEN", e2e);
            applyReadiness(projectId, sessionId, "FINAL_CHECK", e2e);
        } else if (checkResult != null) {
            String finalStatus = normalizeReadinessStatus(checkResult.overallStatus());
            updateFromReadiness(projectId, sessionId, "FINAL_CHECK", finalStatus,
                    "Platform self-check completed: " + checkResult.overallStatus(),
                    Map.of("overallStatus", nullToEmpty(checkResult.overallStatus())));
        }
        recalculateSession(sessionId, checkResult == null
                ? "SDK access self-check completed"
                : "SDK access self-check completed: " + checkResult.overallStatus());
        return requireView(projectId, sessionId);
    }

    private void applyReadiness(Long projectId,
                                String sessionId,
                                String stepKey,
                                ControlAiAssistProjectController.SdkAccessReadiness readiness) {
        if (readiness == null) return;
        updateFromReadiness(projectId, sessionId, stepKey,
                normalizeReadinessStatus(readiness.status()), readiness.message(),
                Map.of("readinessKey", nullToEmpty(readiness.key())));
    }

    private void updateFromReadiness(Long projectId,
                                     String sessionId,
                                     String stepKey,
                                     String status,
                                     String message,
                                     Map<String, Object> evidence) {
        updateStep(projectId, sessionId, stepKey, status, message, List.of(), evidence,
                "platform-self-check", true);
    }

    private void ensureSession(Long projectId, String projectCode, String toolName, String sessionId) {
        List<SessionIdentity> identities = jdbcTemplate.query(
                "SELECT project_id, scenario FROM control_ai_access_session WHERE session_id = ?",
                (rs, rowNum) -> new SessionIdentity(rs.getLong("project_id"), rs.getString("scenario")),
                sessionId);
        if (!identities.isEmpty()) {
            SessionIdentity identity = identities.get(0);
            if (!projectId.equals(identity.projectId()) || !SCENARIO.equals(identity.scenario())) {
                throw new IllegalArgumentException("AI access session does not belong to project " + projectId);
            }
            jdbcTemplate.update("""
                            UPDATE control_ai_access_session
                               SET project_code = ?, tool_name = COALESCE(?, tool_name), updated_at = ?
                             WHERE session_id = ?
                            """,
                    emptyToNull(projectCode), emptyToNull(toolName), Timestamp.valueOf(LocalDateTime.now()), sessionId);
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        try {
            jdbcTemplate.update("""
                            INSERT INTO control_ai_access_session
                                (session_id, project_id, project_code, tool_name, scenario, status,
                                 total_steps, completed_steps, failed_steps, last_message, created_at, updated_at)
                            VALUES (?, ?, ?, ?, ?, 'OPEN', ?, 0, 0, ?, ?, ?)
                            """,
                    sessionId, projectId, emptyToNull(projectCode), emptyToNull(toolName), SCENARIO,
                    SDK_STEPS.size(), "SDK access session is ready", Timestamp.valueOf(now), Timestamp.valueOf(now));
        } catch (DuplicateKeyException ignored) {
            // Another request initialized the deterministic session concurrently.
        }
    }

    private void ensureCanonicalSteps(Long projectId, String sessionId) {
        for (StepDefinition definition : SDK_STEPS) {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(1) FROM control_ai_access_step WHERE session_id = ? AND step_key = ?",
                    Integer.class, sessionId, definition.key());
            if (count != null && count > 0) continue;
            try {
                jdbcTemplate.update("""
                                INSERT INTO control_ai_access_step
                                    (session_id, project_id, step_key, title, status, files_json, evidence_json, updated_at)
                                VALUES (?, ?, ?, ?, 'TODO', '[]', '{}', ?)
                                """,
                        sessionId, projectId, definition.key(), definition.title(),
                        Timestamp.valueOf(LocalDateTime.now()));
            } catch (DuplicateKeyException ignored) {
                // Idempotent initialization under concurrent latest/start requests.
            }
        }
    }

    private void updateStep(Long projectId,
                            String sessionId,
                            String stepKey,
                            String status,
                            String message,
                            List<String> files,
                            Map<String, Object> evidence,
                            String reportedBy,
                            boolean preservePass) {
        String currentStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM control_ai_access_step WHERE session_id = ? AND step_key = ?",
                String.class, sessionId, stepKey);
        if (preservePass && "PASS".equals(currentStatus)) return;
        LocalDateTime now = LocalDateTime.now();
        Timestamp startedAt = "RUNNING".equals(status) || "TODO".equals(currentStatus)
                ? Timestamp.valueOf(now)
                : null;
        Timestamp completedAt = Set.of("PASS", "WARN", "FAIL", "SKIPPED").contains(status)
                ? Timestamp.valueOf(now)
                : null;
        jdbcTemplate.update("""
                        UPDATE control_ai_access_step
                           SET status = ?, message = ?, files_json = ?, evidence_json = ?, reported_by = ?,
                               started_at = COALESCE(started_at, ?), completed_at = ?, updated_at = ?
                         WHERE session_id = ? AND project_id = ? AND step_key = ?
                        """,
                status, emptyToNull(message), writeJson(files), writeJson(evidence), emptyToNull(reportedBy),
                startedAt, completedAt, Timestamp.valueOf(now), sessionId, projectId, stepKey);
    }

    private void recalculateSession(String sessionId, String requestedMessage) {
        ProgressCounts counts = jdbcTemplate.queryForObject("""
                        SELECT COUNT(*) AS total_steps,
                               SUM(CASE WHEN status = 'PASS' THEN 1 ELSE 0 END) AS completed_steps,
                               SUM(CASE WHEN status = 'FAIL' THEN 1 ELSE 0 END) AS failed_steps,
                               SUM(CASE WHEN status = 'RUNNING' THEN 1 ELSE 0 END) AS running_steps,
                               SUM(CASE WHEN status = 'WARN' THEN 1 ELSE 0 END) AS warn_steps
                          FROM control_ai_access_step
                         WHERE session_id = ?
                        """,
                (rs, rowNum) -> new ProgressCounts(
                        rs.getInt("total_steps"),
                        rs.getInt("completed_steps"),
                        rs.getInt("failed_steps"),
                        rs.getInt("running_steps"),
                        rs.getInt("warn_steps")),
                sessionId);
        if (counts == null) return;
        String status;
        if (counts.failed() > 0) status = "FAIL";
        else if (counts.total() > 0 && counts.completed() == counts.total()) status = "PASS";
        else if (counts.running() > 0 || counts.completed() > 0) status = "RUNNING";
        else if (counts.warn() > 0) status = "WARN";
        else status = "OPEN";
        String message = StringUtils.hasText(requestedMessage)
                ? requestedMessage.trim()
                : ("PASS".equals(status) ? "SDK access completed" : "SDK access progress updated");
        jdbcTemplate.update("""
                        UPDATE control_ai_access_session
                           SET status = ?, total_steps = ?, completed_steps = ?, failed_steps = ?,
                               last_message = ?, updated_at = ?
                         WHERE session_id = ?
                        """,
                status, counts.total(), counts.completed(), counts.failed(), message,
                Timestamp.valueOf(LocalDateTime.now()), sessionId);
    }

    private ControlAiAssistProjectController.AiAccessSessionView requireView(Long projectId, String sessionId) {
        SessionRow session = jdbcTemplate.queryForObject("""
                        SELECT session_id, project_id, project_code, tool_name, scenario, target_page_key, target_route,
                               status, total_steps, completed_steps, failed_steps, last_message, created_at, updated_at
                          FROM control_ai_access_session
                         WHERE session_id = ? AND project_id = ?
                        """,
                (rs, rowNum) -> new SessionRow(
                        rs.getString("session_id"), rs.getLong("project_id"), rs.getString("project_code"),
                        rs.getString("tool_name"), rs.getString("scenario"), rs.getString("target_page_key"),
                        rs.getString("target_route"), rs.getString("status"), rs.getInt("total_steps"),
                        rs.getInt("completed_steps"), rs.getInt("failed_steps"), rs.getString("last_message"),
                        rs.getTimestamp("created_at"), rs.getTimestamp("updated_at")),
                sessionId, projectId);
        if (session == null) throw new IllegalStateException("AI access session not found: " + sessionId);
        List<ControlAiAssistProjectController.AiAccessStepView> steps = jdbcTemplate.query("""
                        SELECT step_key, title, status, message, files_json, evidence_json, reported_by,
                               started_at, completed_at, updated_at
                          FROM control_ai_access_step
                         WHERE session_id = ?
                         ORDER BY id ASC
                        """,
                (rs, rowNum) -> new ControlAiAssistProjectController.AiAccessStepView(
                        rs.getString("step_key"), rs.getString("title"), rs.getString("status"),
                        rs.getString("message"), readStringList(rs.getString("files_json")),
                        readObjectMap(rs.getString("evidence_json")), rs.getString("reported_by"),
                        timestampText(rs.getTimestamp("started_at")), timestampText(rs.getTimestamp("completed_at")),
                        timestampText(rs.getTimestamp("updated_at"))),
                sessionId);
        return new ControlAiAssistProjectController.AiAccessSessionView(
                session.sessionId(), session.projectId(), session.projectCode(), session.toolName(), session.scenario(),
                session.targetPageKey(), session.targetRoute(), session.status(), session.totalSteps(),
                session.completedSteps(), session.failedSteps(), session.lastMessage(),
                timestampText(session.createdAt()), timestampText(session.updatedAt()), steps);
    }

    static String canonicalStepKey(String stepKey) {
        if (!StringUtils.hasText(stepKey)) throw new IllegalArgumentException("stepKey is required");
        String normalized = stepKey.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        return switch (normalized) {
            case "project", "project-manifest", "project-recognition" -> "PROJECT";
            case "starter", "backend-starter", "backend-sdk", "reachai-config" -> "STARTER";
            case "gateway", "gateway-route", "gateway-whitelist", "api-management-handoff" -> "GATEWAY";
            case "business-api", "business-service", "business-service-check", "connectivity-check" -> "BUSINESS_API";
            case "embed-token", "embed-token-broker", "frontend", "frontend-embed" -> "EMBED_TOKEN";
            case "final-check", "final-self-check", "self-check", "handoff-summary" -> "FINAL_CHECK";
            default -> throw new IllegalArgumentException("Unsupported SDK access stepKey: " + stepKey);
        };
    }

    private String normalizeReadinessStatus(String status) {
        if ("PASS".equals(status)) return "PASS";
        if ("FAIL".equals(status)) return "FAIL";
        return "WARN";
    }

    private String normalizeStatus(String status, String fallback) {
        String normalized = StringUtils.hasText(status) ? status.trim().toUpperCase(Locale.ROOT) : fallback;
        if (!ALLOWED_STATUSES.contains(normalized)) {
            throw new IllegalArgumentException("Unsupported SDK access status: " + status);
        }
        return normalized;
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().filter(item -> item != null && StringUtils.hasText(String.valueOf(item)))
                .map(item -> String.valueOf(item).trim()).toList();
    }

    private Map<String, Object> objectMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> {
            if (key != null) result.put(String.valueOf(key), item);
        });
        return result;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalArgumentException("SDK access evidence is not JSON serializable", ex);
        }
    }

    private List<String> readStringList(String json) {
        if (!StringUtils.hasText(json)) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private Map<String, Object> readObjectMap(String json) {
        if (!StringUtils.hasText(json)) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private String timestampText(Timestamp timestamp) {
        if (timestamp == null) return null;
        return timestamp.toLocalDateTime().atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    private void requireProjectId(Long projectId) {
        if (projectId == null) throw new IllegalArgumentException("projectId is required");
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String emptyToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) return value.trim();
        }
        return null;
    }

    private record StepDefinition(String key, String title) {}
    private record SessionIdentity(Long projectId, String scenario) {}
    private record ProgressCounts(int total, int completed, int failed, int running, int warn) {}
    private record SessionRow(String sessionId,
                              Long projectId,
                              String projectCode,
                              String toolName,
                              String scenario,
                              String targetPageKey,
                              String targetRoute,
                              String status,
                              int totalSteps,
                              int completedSteps,
                              int failedSteps,
                              String lastMessage,
                              Timestamp createdAt,
                              Timestamp updatedAt) {}
}
