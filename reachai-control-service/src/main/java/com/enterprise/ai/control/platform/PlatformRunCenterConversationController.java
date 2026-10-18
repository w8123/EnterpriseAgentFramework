package com.enterprise.ai.control.platform;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformPermissions;
import com.enterprise.ai.control.identity.PlatformRequestAuthorization;
import com.enterprise.ai.control.platform.PlatformConversationViews.ConversationDetailView;
import com.enterprise.ai.control.platform.PlatformConversationViews.ConversationRunView;
import com.enterprise.ai.control.platform.PlatformConversationViews.ConversationSummaryView;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 运行中心「对话」视角。业务用户只通过嵌入式对话使用 Agent，所以对话正文以 Control 的嵌入式
 * 对话事件为准；每一轮的运行状态从 Runtime 的根运行读取。Runtime 不可用时仍返回对话正文。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class PlatformRunCenterConversationController {

    private static final int CANDIDATE_SESSIONS = 300;
    private static final int MAX_LIST = 100;
    private static final int MAX_EVENTS = 1000;
    private static final int MAX_RUNS = 100;
    private static final String PROJECT_SCOPE = "PROJECT";

    private final PlatformRequestAuthorization requestAuthorization;
    private final PlatformEmbedSessionMapper sessionMapper;
    private final PlatformEmbedChatEventMapper chatEventMapper;
    private final RuntimeProxyClient runtimeProxyClient;

    @GetMapping("/api/runops/conversations")
    public List<ConversationSummaryView> list(HttpServletRequest request,
                                              @RequestParam(required = false) String projectCode,
                                              @RequestParam(required = false) String agentId,
                                              @RequestParam(required = false) String keyword,
                                              @RequestParam(defaultValue = "false") boolean problemsOnly,
                                              @RequestParam(defaultValue = "7") int days,
                                              @RequestParam(defaultValue = "50") int limit) {
        PlatformAuthenticatedSession session = StringUtils.hasText(projectCode)
                ? requestAuthorization.requireResourcePermission(
                        request, PlatformPermissions.RUNOPS_READ, PROJECT_SCOPE, null, projectCode.trim())
                : requestAuthorization.requirePermission(request, PlatformPermissions.RUNOPS_READ);
        int safeDays = Math.max(1, Math.min(days, 90));
        String search = StringUtils.hasText(keyword) ? keyword.trim() : null;
        LambdaQueryWrapper<PlatformEmbedSessionEntity> wrapper = new LambdaQueryWrapper<PlatformEmbedSessionEntity>()
                .ge(PlatformEmbedSessionEntity::getCreatedAt, LocalDateTime.now().minusDays(safeDays))
                .eq(StringUtils.hasText(projectCode), PlatformEmbedSessionEntity::getProjectCode, trim(projectCode))
                .eq(StringUtils.hasText(agentId), PlatformEmbedSessionEntity::getAgentId, trim(agentId))
                .and(search != null, query -> query
                        .like(PlatformEmbedSessionEntity::getExternalUserId, search)
                        .or().like(PlatformEmbedSessionEntity::getExternalUserName, search)
                        .or().eq(PlatformEmbedSessionEntity::getSessionId, search)
                        .or().like(PlatformEmbedSessionEntity::getRoute, search))
                .orderByDesc(PlatformEmbedSessionEntity::getId)
                .last("limit " + CANDIDATE_SESSIONS);
        List<PlatformEmbedSessionEntity> sessions = sessionMapper.selectList(wrapper).stream()
                .filter(row -> canRead(session, row.getProjectCode()))
                .toList();
        if (sessions.isEmpty()) return List.of();

        List<String> sessionIds = sessions.stream().map(PlatformEmbedSessionEntity::getSessionId).toList();
        Map<String, PlatformEmbedConversationStats> stats = chatEventMapper.selectConversationStats(sessionIds).stream()
                .collect(Collectors.toMap(PlatformEmbedConversationStats::getSessionId, Function.identity(),
                        (left, right) -> left));
        List<PlatformEmbedSessionEntity> withQuestions = sessions.stream()
                .filter(row -> questionCount(stats.get(row.getSessionId())) > 0)
                .toList();
        Map<String, Map<String, Object>> runStats = runStats(
                withQuestions.stream().map(PlatformEmbedSessionEntity::getSessionId).limit(MAX_LIST).toList(),
                safeDays);
        int safeLimit = Math.max(1, Math.min(limit, MAX_LIST));
        return withQuestions.stream()
                .map(row -> summary(row, stats.get(row.getSessionId()), runStats.get(row.getSessionId())))
                .filter(view -> !problemsOnly || hasProblem(view))
                .sorted((left, right) -> compareDesc(left.lastActiveAt(), right.lastActiveAt()))
                .limit(safeLimit)
                .toList();
    }

    @GetMapping("/api/runops/conversations/{sessionId}")
    public ConversationDetailView detail(HttpServletRequest request, @PathVariable String sessionId) {
        PlatformAuthenticatedSession session = requestAuthorization.requirePermission(
                request, PlatformPermissions.RUNOPS_READ);
        PlatformEmbedSessionEntity row = sessionMapper.selectOne(new LambdaQueryWrapper<PlatformEmbedSessionEntity>()
                .eq(PlatformEmbedSessionEntity::getSessionId, sessionId)
                .last("limit 1"));
        if (row == null || !canRead(session, row.getProjectCode())) {
            // 无权访问和不存在返回同一结果，不暴露其他项目的会话 ID。
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "conversation not found");
        }
        List<PlatformEmbedChatEventEntity> events = chatEventMapper.selectList(
                new LambdaQueryWrapper<PlatformEmbedChatEventEntity>()
                        .eq(PlatformEmbedChatEventEntity::getSessionId, row.getSessionId())
                        .orderByAsc(PlatformEmbedChatEventEntity::getId)
                        .last("limit " + MAX_EVENTS));
        List<ConversationRunView> runs = null;
        try {
            ResponseEntity<List<Map<String, Object>>> response =
                    runtimeProxyClient.runOpsSessionRuns(row.getSessionId(), MAX_RUNS, 90);
            if (response != null && response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                runs = response.getBody().stream().map(PlatformRunCenterConversationController::runView).toList();
            }
        } catch (RuntimeException ex) {
            log.warn("[RunCenter] conversation runs unavailable for session {}: {}", row.getSessionId(), ex.getMessage());
        }
        var turns = PlatformConversationTurnAssembler.assemble(events, runs == null ? List.of() : runs);
        PlatformEmbedConversationStats stats = new PlatformEmbedConversationStats();
        stats.setQuestionCount((int) turns.stream().filter(turn -> turn.question() != null).count());
        stats.setLastEventAt(events.isEmpty() ? null : events.get(events.size() - 1).getCreatedAt());
        turns.stream().filter(turn -> turn.question() != null).reduce((first, second) -> second)
                .ifPresent(turn -> stats.setLastQuestion(turn.question()));
        Map<String, Object> runSummary = null;
        if (runs != null) {
            runSummary = new HashMap<>();
            runSummary.put("runCount", runs.size());
            runSummary.put("failedCount", (int) runs.stream().filter(run -> isFailed(run.status())).count());
            runSummary.put("openCount", (int) runs.stream().filter(run -> isOpen(run.status())).count());
        }
        return new ConversationDetailView(summary(row, stats, runSummary), turns, runs != null);
    }

    private Map<String, Map<String, Object>> runStats(List<String> sessionIds, int days) {
        if (sessionIds.isEmpty()) return Map.of();
        try {
            ResponseEntity<List<Map<String, Object>>> response =
                    runtimeProxyClient.runOpsSessionStats(sessionIds, days);
            if (response == null || !response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                return Map.of();
            }
            Map<String, Map<String, Object>> result = new HashMap<>();
            for (Map<String, Object> item : response.getBody()) {
                Object id = item.get("sessionId");
                if (id != null) result.put(String.valueOf(id), item);
            }
            return result;
        } catch (RuntimeException ex) {
            log.warn("[RunCenter] conversation run stats unavailable: {}", ex.getMessage());
            return Map.of();
        }
    }

    private boolean canRead(PlatformAuthenticatedSession session, String projectCode) {
        return session != null && session.hasResourcePermission(
                PlatformPermissions.RUNOPS_READ, PROJECT_SCOPE, null, trim(projectCode));
    }

    private static ConversationSummaryView summary(PlatformEmbedSessionEntity row,
                                                   PlatformEmbedConversationStats stats,
                                                   Map<String, Object> runStats) {
        LocalDateTime lastActive = stats != null && stats.getLastEventAt() != null
                ? stats.getLastEventAt() : row.getUpdatedAt();
        return new ConversationSummaryView(
                row.getSessionId(),
                row.getProjectCode(),
                row.getAgentId(),
                row.getExternalUserId(),
                row.getExternalUserName(),
                row.getPageKey(),
                row.getRoute(),
                row.getOrigin(),
                questionCount(stats),
                stats == null ? null : abbreviate(stats.getLastQuestion()),
                row.getCreatedAt(),
                lastActive,
                runStats == null ? null : integer(runStats.get("runCount")),
                runStats == null ? null : integer(runStats.get("failedCount")),
                runStats == null ? null : integer(runStats.get("openCount")));
    }

    static ConversationRunView runView(Map<String, Object> run) {
        return new ConversationRunView(
                text(run.get("traceId")),
                text(run.get("status")),
                text(run.get("suspensionReason")),
                text(run.get("errorCode")),
                text(run.get("errorMessage")),
                integer(run.get("latencyMs")),
                text(run.get("startedAt")));
    }

    private static boolean hasProblem(ConversationSummaryView view) {
        return (view.failedRunCount() != null && view.failedRunCount() > 0)
                || (view.openRunCount() != null && view.openRunCount() > 0);
    }

    private static boolean isFailed(String status) {
        return "FAILED".equals(status) || "TIMED_OUT".equals(status);
    }

    private static boolean isOpen(String status) {
        return "RUNNING".equals(status) || "SUSPENDED".equals(status);
    }

    private static int questionCount(PlatformEmbedConversationStats stats) {
        return stats == null || stats.getQuestionCount() == null ? 0 : stats.getQuestionCount();
    }

    private static int compareDesc(LocalDateTime left, LocalDateTime right) {
        if (left == null) return right == null ? 0 : 1;
        if (right == null) return -1;
        return right.compareTo(left);
    }

    private static String abbreviate(String value) {
        if (value == null) return null;
        return value.length() <= 120 ? value : value.substring(0, 120) + "…";
    }

    private static Integer integer(Object value) {
        if (value instanceof Number number) return number.intValue();
        if (value instanceof String text && StringUtils.hasText(text)) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String text(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }
}
