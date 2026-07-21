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
    private static final String PAGE_ASSISTANT_SCENARIO = "PAGE_ASSISTANT";
    private static final List<StepDefinition> SDK_STEPS = List.of(
            new StepDefinition("PROJECT", "项目识别"),
            new StepDefinition("STARTER", "后端 Starter"),
            new StepDefinition("GATEWAY", "网关路由"),
            new StepDefinition("BUSINESS_API", "业务服务校验"),
            new StepDefinition("EMBED_TOKEN", "前端 Embed Token"),
            new StepDefinition("FINAL_CHECK", "最终自检")
    );
    private static final List<StepDefinition> PAGE_ASSISTANT_STEPS = List.of(
            new StepDefinition("page-manifest", "读取页面助手接入清单"),
            new StepDefinition("route-detection", "确认业务前端路由"),
            new StepDefinition("page-structure", "识别页面结构"),
            new StepDefinition("action-design", "设计页面动作"),
            new StepDefinition("frontend-handler", "注册前端页面动作 handler"),
            new StepDefinition("page-registry", "同步页面动作目录"),
            new StepDefinition("browser-verify", "验证页面动作连通性"),
            new StepDefinition("handoff-summary", "提交修改清单和待办"),
            new StepDefinition("workflow-ai-coding-draft", "Workflow AI Coding 生成草稿")
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

    @Transactional
    public ControlAiAssistProjectController.AiAccessSessionView openPageAssistantSession(
            Long projectId,
            String projectCode,
            String toolName,
            String requestedSessionId,
            String pageKey,
            String routePattern,
            List<String> actionKeys) {
        requireProjectId(projectId);
        String normalizedPageKey = emptyToNull(pageKey);
        String sessionId = StringUtils.hasText(requestedSessionId)
                ? requestedSessionId.trim()
                : "page-assistant-" + projectId
                + (normalizedPageKey == null ? "" : "-" + normalizedPageKey);
        ensurePageAssistantSession(
                projectId,
                projectCode,
                toolName,
                sessionId,
                normalizedPageKey,
                emptyToNull(routePattern),
                actionKeys == null ? List.of() : actionKeys);
        ensurePageAssistantSteps(projectId, sessionId);
        return requireView(projectId, sessionId);
    }

    @Transactional
    public ControlAiAssistProjectController.AiAccessSessionView latestPageAssistantSession(
            Long projectId,
            String projectCode,
            String pageKey) {
        List<String> sessionIds = findPageAssistantSessionIds(projectId, pageKey);
        if (sessionIds.isEmpty()) {
            return openPageAssistantSession(projectId, projectCode, null, null, pageKey, null, List.of());
        }
        String sessionId = sessionIds.get(0);
        ensurePageAssistantSteps(projectId, sessionId);
        return requireView(projectId, sessionId);
    }

    @Transactional
    public List<ControlAiAssistProjectController.PageAssistantSessionSummary> listPageAssistantSessions(
            Long projectId,
            String projectCode,
            String pageKey) {
        List<String> sessionIds = findPageAssistantSessionIds(projectId, pageKey);
        if (sessionIds.isEmpty()) {
            ControlAiAssistProjectController.AiAccessSessionView opened =
                    openPageAssistantSession(projectId, projectCode, null, null, pageKey, null, List.of());
            return List.of(toPageAssistantSummary(opened));
        }
        return sessionIds.stream().map(sessionId -> {
            ensurePageAssistantSteps(projectId, sessionId);
            return toPageAssistantSummary(requireView(projectId, sessionId));
        }).toList();
    }

    @Transactional
    public ControlAiAssistProjectController.AiAccessSessionView reportPageAssistantStep(
            Long projectId,
            String projectCode,
            String sessionId,
            String stepKey,
            Map<String, ?> report,
            String defaultReporter) {
        openPageAssistantSession(projectId, projectCode, null, sessionId, null, null, List.of());
        String canonicalKey = canonicalPageAssistantStepKey(stepKey);
        String status = normalizeStatus(text(report == null ? null : report.get("status")), "PASS");
        String message = text(report == null ? null : report.get("message"));
        List<String> files = stringList(report == null ? null : report.get("files"));
        Map<String, Object> evidence = objectMap(report == null ? null : report.get("evidence"));
        String reportedBy = firstText(
                text(report == null ? null : report.get("reportedBy")),
                defaultReporter,
                "ai-coding");
        updatePageAssistantTargetFromEvidence(projectId, sessionId, evidence);
        updateStep(projectId, sessionId, canonicalKey, status, message, files, evidence, reportedBy, false);
        recalculateSession(sessionId, message);
        return requireView(projectId, sessionId);
    }

    @Transactional
    public ControlAiAssistProjectController.AiAccessSessionView bindPageAssistantTarget(
            Long projectId,
            String projectCode,
            String sessionId,
            String pageKey,
            String routePattern,
            List<String> actionKeys) {
        openPageAssistantSession(
                projectId,
                projectCode,
                null,
                sessionId,
                pageKey,
                routePattern,
                actionKeys == null ? List.of() : actionKeys);
        updateStep(
                projectId,
                sessionId,
                "route-detection",
                StringUtils.hasText(routePattern) ? "PASS" : "WARN",
                StringUtils.hasText(routePattern) ? "Target page and route are bound." : "Target page is bound without a route.",
                List.of(),
                Map.of(
                        "pageKey", nullToEmpty(pageKey),
                        "routePattern", nullToEmpty(routePattern),
                        "actionKeys", actionKeys == null ? List.of() : actionKeys),
                "page-assistant-target",
                false);
        recalculateSession(sessionId, "Page assistant target bound.");
        return requireView(projectId, sessionId);
    }

    @Transactional
    public ControlAiAssistProjectController.AiAccessSessionView applyPageAssistantCatalogSync(
            Long projectId,
            String projectCode,
            String sessionId,
            ControlAiAssistProjectController.PageAssistantCatalogSyncRequest request,
            List<String> registeredActionKeys) {
        bindPageAssistantTarget(
                projectId,
                projectCode,
                sessionId,
                request == null ? null : request.pageKey(),
                request == null ? null : request.routePattern(),
                registeredActionKeys);
        updateStep(
                projectId,
                sessionId,
                "page-registry",
                registeredActionKeys == null || registeredActionKeys.isEmpty() ? "WARN" : "PASS",
                registeredActionKeys == null || registeredActionKeys.isEmpty()
                        ? "Page registered without actions."
                        : "Page and action catalog persisted.",
                List.of(),
                Map.of("actionKeys", registeredActionKeys == null ? List.of() : registeredActionKeys),
                "page-assistant-catalog",
                false);
        recalculateSession(sessionId, "Page assistant catalog synchronized.");
        return requireView(projectId, sessionId);
    }

    @Transactional
    public ControlAiAssistProjectController.AiAccessSessionView applyPageAssistantRegistration(
            Long projectId,
            String projectCode,
            ControlAiAssistProjectController.PageAssistantPageRegisterRequest request,
            List<String> registeredActionKeys) {
        if (request == null) {
            throw new IllegalArgumentException("page assistant register request is required");
        }
        ControlAiAssistProjectController.AiAccessSessionView opened = openPageAssistantSession(
                projectId,
                projectCode,
                request.toolName(),
                request.sessionId(),
                request.pageKey(),
                request.routePattern(),
                registeredActionKeys);
        String sessionId = opened.sessionId();
        List<String> files = request.files() == null ? List.of() : request.files().stream()
                .filter(file -> file != null && StringUtils.hasText(file.path()))
                .map(file -> file.path().trim())
                .toList();
        boolean pageComponentPresent = hasFileRole(request.files(), "page-component");
        boolean handlerPresent = hasFileRole(request.files(), "bridge")
                || hasFileRole(request.files(), "bridge-or-handler")
                || hasFileRole(request.files(), "page-actions");

        updateStep(projectId, sessionId, "page-manifest", "PASS",
                "Page Assistant registration report received.", files,
                Map.of("pageKey", request.pageKey(), "framework", nullToEmpty(request.framework())),
                "page-assistant-register", false);
        updateStep(projectId, sessionId, "route-detection",
                StringUtils.hasText(request.routePattern()) ? "PASS" : "WARN",
                StringUtils.hasText(request.routePattern()) ? "Target route registered." : "Route pattern is missing.",
                files, Map.of("routePattern", nullToEmpty(request.routePattern())),
                "page-assistant-register", false);
        updateStep(projectId, sessionId, "page-structure",
                pageComponentPresent ? "PASS" : "WARN",
                pageComponentPresent ? "Page component evidence reported." : "Page component evidence is missing.",
                files, Map.of("pageComponentPresent", pageComponentPresent),
                "page-assistant-register", false);
        updateStep(projectId, sessionId, "action-design",
                registeredActionKeys == null || registeredActionKeys.isEmpty() ? "WARN" : "PASS",
                registeredActionKeys == null || registeredActionKeys.isEmpty()
                        ? "No page actions were reported."
                        : "Page actions were declared.",
                files, Map.of("actionKeys", registeredActionKeys == null ? List.of() : registeredActionKeys),
                "page-assistant-register", false);
        updateStep(projectId, sessionId, "frontend-handler",
                handlerPresent ? "PASS" : "WARN",
                handlerPresent ? "Frontend handler evidence reported." : "Frontend handler evidence is missing.",
                files, Map.of("handlerPresent", handlerPresent),
                "page-assistant-register", false);
        updateStep(projectId, sessionId, "page-registry", "PASS",
                "Page and action catalog persisted.", files,
                Map.of("actionKeys", registeredActionKeys == null ? List.of() : registeredActionKeys),
                "page-assistant-register", false);

        Map<String, Object> runtimeVerification = nestedMap(request.verification(), "browserRuntime");
        String runtimeStatus = normalizeRuntimeVerificationStatus(text(runtimeVerification.get("status")));
        updateStep(projectId, sessionId, "browser-verify", runtimeStatus,
                firstText(text(runtimeVerification.get("message")), "Browser runtime verification was not provided."),
                files, runtimeVerification,
                "page-assistant-register", false);
        updateStep(projectId, sessionId, "handoff-summary",
                StringUtils.hasText(request.handoffSummary()) ? "PASS" : "WARN",
                firstText(request.handoffSummary(), "Page Assistant handoff summary is missing."),
                files, Map.of(), "page-assistant-register", false);
        recalculateSession(sessionId, firstText(request.handoffSummary(), "Page assistant registered."));
        return requireView(projectId, sessionId);
    }

    private void ensurePageAssistantSession(
            Long projectId,
            String projectCode,
            String toolName,
            String sessionId,
            String pageKey,
            String routePattern,
            List<String> actionKeys) {
        List<SessionIdentity> identities = jdbcTemplate.query(
                "SELECT project_id, scenario FROM control_ai_access_session WHERE session_id = ?",
                (rs, rowNum) -> new SessionIdentity(rs.getLong("project_id"), rs.getString("scenario")),
                sessionId);
        String metadataJson = writeJson(Map.of("actionKeys", normalizeActionKeys(actionKeys)));
        if (!identities.isEmpty()) {
            SessionIdentity identity = identities.get(0);
            if (!projectId.equals(identity.projectId()) || !PAGE_ASSISTANT_SCENARIO.equals(identity.scenario())) {
                throw new IllegalArgumentException("Page Assistant session does not belong to project " + projectId);
            }
            jdbcTemplate.update("""
                            UPDATE control_ai_access_session
                               SET project_code = ?, tool_name = COALESCE(?, tool_name),
                                   target_page_key = COALESCE(?, target_page_key),
                                   target_route = COALESCE(?, target_route),
                                   metadata_json = CASE WHEN ? THEN ? ELSE metadata_json END,
                                   updated_at = ?
                             WHERE session_id = ?
                            """,
                    emptyToNull(projectCode),
                    emptyToNull(toolName),
                    emptyToNull(pageKey),
                    emptyToNull(routePattern),
                    actionKeys != null && !actionKeys.isEmpty(),
                    metadataJson,
                    Timestamp.valueOf(LocalDateTime.now()),
                    sessionId);
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        try {
            jdbcTemplate.update("""
                            INSERT INTO control_ai_access_session
                                (session_id, project_id, project_code, tool_name, scenario,
                                 target_page_key, target_route, metadata_json, status,
                                 total_steps, completed_steps, failed_steps, last_message, created_at, updated_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', ?, 0, 0, ?, ?, ?)
                            """,
                    sessionId,
                    projectId,
                    emptyToNull(projectCode),
                    emptyToNull(toolName),
                    PAGE_ASSISTANT_SCENARIO,
                    emptyToNull(pageKey),
                    emptyToNull(routePattern),
                    metadataJson,
                    PAGE_ASSISTANT_STEPS.size(),
                    "Page Assistant session is ready",
                    Timestamp.valueOf(now),
                    Timestamp.valueOf(now));
        } catch (DuplicateKeyException ignored) {
            // Another request initialized the deterministic session concurrently.
        }
    }

    private void ensurePageAssistantSteps(Long projectId, String sessionId) {
        for (StepDefinition definition : PAGE_ASSISTANT_STEPS) {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(1) FROM control_ai_access_step WHERE session_id = ? AND step_key = ?",
                    Integer.class,
                    sessionId,
                    definition.key());
            if (count != null && count > 0) continue;
            try {
                jdbcTemplate.update("""
                                INSERT INTO control_ai_access_step
                                    (session_id, project_id, step_key, title, status, files_json, evidence_json, updated_at)
                                VALUES (?, ?, ?, ?, 'TODO', '[]', '{}', ?)
                                """,
                        sessionId,
                        projectId,
                        definition.key(),
                        definition.title(),
                        Timestamp.valueOf(LocalDateTime.now()));
            } catch (DuplicateKeyException ignored) {
                // Idempotent initialization under concurrent manifest/session requests.
            }
        }
    }

    private List<String> findPageAssistantSessionIds(Long projectId, String pageKey) {
        StringBuilder sql = new StringBuilder("""
                SELECT session_id
                  FROM control_ai_access_session
                 WHERE project_id = ? AND scenario = ?
                """);
        List<Object> args = new java.util.ArrayList<>();
        args.add(projectId);
        args.add(PAGE_ASSISTANT_SCENARIO);
        if (StringUtils.hasText(pageKey)) {
            sql.append(" AND target_page_key = ?");
            args.add(pageKey.trim());
        }
        sql.append(" ORDER BY updated_at DESC, id DESC LIMIT 200");
        return jdbcTemplate.query(sql.toString(),
                (rs, rowNum) -> rs.getString("session_id"),
                args.toArray());
    }

    private ControlAiAssistProjectController.PageAssistantSessionSummary toPageAssistantSummary(
            ControlAiAssistProjectController.AiAccessSessionView session) {
        Map<String, Object> metadata = jdbcTemplate.queryForObject(
                "SELECT metadata_json FROM control_ai_access_session WHERE session_id = ?",
                (rs, rowNum) -> readObjectMap(rs.getString("metadata_json")),
                session.sessionId());
        int actionCount = stringList(metadata == null ? null : metadata.get("actionKeys")).size();
        String completionState;
        if (!StringUtils.hasText(session.targetPageKey())) {
            completionState = "WAITING_TARGET";
        } else if (session.failedSteps() > 0 || "FAIL".equals(session.status())) {
            completionState = "BLOCKED";
        } else if (session.steps().stream().anyMatch(step ->
                "browser-verify".equals(step.stepKey()) && "PASS".equals(step.status()))) {
            completionState = "COMPLETED";
        } else {
            completionState = "IN_PROGRESS";
        }
        return new ControlAiAssistProjectController.PageAssistantSessionSummary(
                session.sessionId(),
                session.projectId(),
                session.projectCode(),
                session.toolName(),
                session.targetPageKey(),
                session.targetRoute(),
                session.status(),
                completionState,
                session.totalSteps(),
                session.completedSteps(),
                session.failedSteps(),
                actionCount,
                session.lastMessage(),
                session.updatedAt(),
                session.steps());
    }

    private void updatePageAssistantTargetFromEvidence(
            Long projectId,
            String sessionId,
            Map<String, Object> evidence) {
        if (evidence == null || evidence.isEmpty()) return;
        Map<String, Object> target = nestedMap(evidence, "target");
        Map<String, Object> source = target.isEmpty() ? evidence : target;
        String pageKey = firstText(text(source.get("pageKey")), text(source.get("targetPageKey")));
        String routePattern = firstText(text(source.get("routePattern")), text(source.get("route")));
        List<String> actionKeys = stringList(source.get("actionKeys"));
        if (!StringUtils.hasText(pageKey) && !StringUtils.hasText(routePattern) && actionKeys.isEmpty()) return;
        jdbcTemplate.update("""
                        UPDATE control_ai_access_session
                           SET target_page_key = COALESCE(?, target_page_key),
                               target_route = COALESCE(?, target_route),
                               metadata_json = CASE WHEN ? THEN ? ELSE metadata_json END,
                               updated_at = ?
                         WHERE session_id = ? AND project_id = ?
                        """,
                emptyToNull(pageKey),
                emptyToNull(routePattern),
                !actionKeys.isEmpty(),
                writeJson(Map.of("actionKeys", actionKeys)),
                Timestamp.valueOf(LocalDateTime.now()),
                sessionId,
                projectId);
    }

    private static boolean hasFileRole(
            List<ControlAiAssistProjectController.PageAssistantFileEvidence> files,
            String role) {
        if (files == null) return false;
        return files.stream().anyMatch(file -> file != null
                && role.equalsIgnoreCase(nullToEmpty(file.role()))
                && !Boolean.FALSE.equals(file.exists()));
    }

    private Map<String, Object> nestedMap(Map<String, Object> root, String key) {
        if (root == null) return Map.of();
        return objectMap(root.get(key));
    }

    private static String normalizeRuntimeVerificationStatus(String status) {
        if (!StringUtils.hasText(status)) return "WARN";
        return switch (status.trim().toUpperCase(Locale.ROOT)) {
            case "PASS", "SUCCESS", "DONE", "COMPLETED" -> "PASS";
            case "FAIL", "ERROR" -> "FAIL";
            case "SKIPPED" -> "SKIPPED";
            default -> "WARN";
        };
    }

    private static List<String> normalizeActionKeys(List<String> actionKeys) {
        if (actionKeys == null) return List.of();
        return actionKeys.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
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

    static String canonicalPageAssistantStepKey(String stepKey) {
        if (!StringUtils.hasText(stepKey)) throw new IllegalArgumentException("stepKey is required");
        String normalized = stepKey.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        return switch (normalized) {
            case "page-manifest", "manifest" -> "page-manifest";
            case "route-detection", "target-page", "target" -> "route-detection";
            case "page-structure", "structure" -> "page-structure";
            case "action-design", "actions" -> "action-design";
            case "frontend-handler", "bridge-scaffold", "handler" -> "frontend-handler";
            case "page-registry", "catalog-sync", "action-catalog", "catalog" -> "page-registry";
            case "browser-verify", "browser-verify-static", "browser-verify-runtime", "self-check" -> "browser-verify";
            case "handoff-summary", "handoff" -> "handoff-summary";
            case "workflow-ai-coding-draft", "workflow-draft" -> "workflow-ai-coding-draft";
            default -> throw new IllegalArgumentException("Unsupported Page Assistant stepKey: " + stepKey);
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
