package com.enterprise.ai.control.aiassist;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persists Page Assistant onboarding reports into the Control-owned page/action catalog.
 */
@Service
@RequiredArgsConstructor
public class ControlPageAssistantCatalogService {

    private static final String AI_CODING_ORIGIN = "ai-coding";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Transactional
    public CatalogRegistration register(
            String projectCode,
            ControlAiAssistProjectController.PageAssistantPageRegisterRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("page assistant register request is required");
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "AI_CODING");
        metadata.put("sessionId", nullToEmpty(request.sessionId()));
        metadata.put("toolName", nullToEmpty(request.toolName()));
        metadata.put("framework", nullToEmpty(request.framework()));
        metadata.put("frameworkVersion", nullToEmpty(request.frameworkVersion()));
        metadata.put("bridgeGlobal", nullToEmpty(request.bridgeGlobal()));
        return registerCatalog(
                projectCode,
                request.pageKey(),
                request.pageName(),
                request.routePattern(),
                AI_CODING_ORIGIN,
                null,
                request.replaceActions(),
                request.actions(),
                metadata);
    }

    @Transactional
    public CatalogRegistration register(
            String projectCode,
            ControlAiAssistProjectController.PageAssistantCatalogSyncRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("page assistant catalog sync request is required");
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "AI_CODING");
        if (request.metadata() != null) {
            metadata.putAll(request.metadata());
        }
        return registerCatalog(
                projectCode,
                request.pageKey(),
                request.name(),
                request.routePattern(),
                firstText(request.origin(), AI_CODING_ORIGIN),
                request.pageInstanceId(),
                request.replaceActions(),
                request.actions(),
                metadata);
    }

    private CatalogRegistration registerCatalog(
            String projectCode,
            String pageKey,
            String pageName,
            String routePattern,
            String origin,
            String pageInstanceId,
            Boolean replaceActions,
            List<ControlAiAssistProjectController.PageAssistantCatalogActionRequest> actions,
            Map<String, Object> metadata) {
        String normalizedProjectCode = requiredText(projectCode, "projectCode is required");
        String normalizedPageKey = requiredText(pageKey, "pageKey is required");
        String normalizedOrigin = firstText(origin, AI_CODING_ORIGIN);
        String normalizedPageName = firstText(pageName, normalizedPageKey);
        String appId = normalizedProjectCode;
        LocalDateTime now = LocalDateTime.now();

        List<Long> pageIds = jdbcTemplate.query(
                "SELECT id FROM control_page_registry WHERE project_code = ? AND page_key = ? AND origin = ?",
                (rs, rowNum) -> rs.getLong("id"),
                normalizedProjectCode,
                normalizedPageKey,
                normalizedOrigin);
        if (pageIds.isEmpty()) {
            jdbcTemplate.update("""
                            INSERT INTO control_page_registry
                                (project_code, app_id, page_key, name, route_pattern, origin,
                                 current_page_instance_id, status, last_seen_at, metadata_json)
                            VALUES (?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?)
                            """,
                    normalizedProjectCode,
                    appId,
                    normalizedPageKey,
                    normalizedPageName,
                    emptyToNull(routePattern),
                    normalizedOrigin,
                    emptyToNull(pageInstanceId),
                    Timestamp.valueOf(now),
                    writeJson(metadata));
        } else {
            jdbcTemplate.update("""
                            UPDATE control_page_registry
                               SET app_id = ?, name = ?, route_pattern = ?,
                                   current_page_instance_id = COALESCE(?, current_page_instance_id),
                                   status = 'ACTIVE', last_seen_at = ?, metadata_json = ?
                             WHERE id = ?
                            """,
                    appId,
                    normalizedPageName,
                    emptyToNull(routePattern),
                    emptyToNull(pageInstanceId),
                    Timestamp.valueOf(now),
                    writeJson(metadata),
                    pageIds.get(0));
        }

        if (!Boolean.FALSE.equals(replaceActions)) {
            jdbcTemplate.update(
                    "DELETE FROM control_page_action_registry WHERE project_code = ? AND page_key = ?",
                    normalizedProjectCode,
                    normalizedPageKey);
        }

        List<ControlAiAssistProjectController.PageAssistantCatalogActionRequest> normalizedActions =
                actions == null ? List.of() : actions;
        for (ControlAiAssistProjectController.PageAssistantCatalogActionRequest action : normalizedActions) {
            upsertAction(normalizedProjectCode, appId, normalizedPageKey, action, now);
        }

        List<String> actionKeys = normalizedActions.stream()
                .filter(action -> action != null && StringUtils.hasText(action.actionKey()))
                .map(action -> action.actionKey().trim())
                .distinct()
                .toList();
        return new CatalogRegistration(
                normalizedProjectCode,
                appId,
                normalizedPageKey,
                normalizedPageName,
                emptyToNull(routePattern),
                actionKeys);
    }

    private void upsertAction(
            String projectCode,
            String appId,
            String pageKey,
            ControlAiAssistProjectController.PageAssistantCatalogActionRequest action,
            LocalDateTime now) {
        if (action == null) {
            throw new IllegalArgumentException("page action is required");
        }
        String actionKey = requiredText(action.actionKey(), "actionKey is required");
        String title = firstText(action.title(), actionKey);
        List<Long> actionIds = jdbcTemplate.query(
                "SELECT id FROM control_page_action_registry WHERE project_code = ? AND page_key = ? AND action_key = ?",
                (rs, rowNum) -> rs.getLong("id"),
                projectCode,
                pageKey,
                actionKey);
        Object[] values = {
                appId,
                title,
                emptyToNull(action.description()),
                Boolean.TRUE.equals(action.confirmRequired()),
                writeJson(action.inputSchema() == null ? Map.of() : action.inputSchema()),
                writeJson(action.outputSchema() == null ? Map.of() : action.outputSchema()),
                writeJson(action.sampleArgs() == null ? Map.of() : action.sampleArgs()),
                writeJson(action.allowedAgentIds() == null ? List.of() : action.allowedAgentIds()),
                writeJson(actionMetadata(action.metadata())),
                Timestamp.valueOf(now)
        };
        if (actionIds.isEmpty()) {
            jdbcTemplate.update("""
                            INSERT INTO control_page_action_registry
                                (project_code, app_id, page_key, action_key, title, description,
                                 confirm_required, input_schema_json, output_schema_json, sample_args_json,
                                 allowed_agent_ids_json, metadata_json, status, last_seen_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?)
                            """,
                    projectCode,
                    values[0],
                    pageKey,
                    actionKey,
                    values[1],
                    values[2],
                    values[3],
                    values[4],
                    values[5],
                    values[6],
                    values[7],
                    values[8],
                    values[9]);
            return;
        }
        jdbcTemplate.update("""
                        UPDATE control_page_action_registry
                           SET app_id = ?, title = ?, description = ?, confirm_required = ?,
                               input_schema_json = ?, output_schema_json = ?, sample_args_json = ?,
                               allowed_agent_ids_json = ?, metadata_json = ?, status = 'ACTIVE', last_seen_at = ?
                         WHERE id = ?
                        """,
                values[0], values[1], values[2], values[3], values[4], values[5], values[6],
                values[7], values[8], values[9], actionIds.get(0));
    }

    private Map<String, Object> actionMetadata(Map<String, Object> metadata) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("source", "AI_CODING");
        if (metadata != null) {
            result.putAll(metadata);
        }
        return result;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalArgumentException("page catalog data is not JSON serializable", ex);
        }
    }

    private static String requiredText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private static String firstText(String first, String fallback) {
        return StringUtils.hasText(first) ? first.trim() : fallback;
    }

    private static String emptyToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    public record CatalogRegistration(
            String projectCode,
            String appId,
            String pageKey,
            String pageName,
            String routePattern,
            List<String> actionKeys) {
    }
}
