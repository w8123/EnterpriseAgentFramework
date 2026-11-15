package com.enterprise.ai.runtime.execution;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.runtime.execution.interaction.WorkflowApprovalAssignment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Named-approver side of HUMAN_APPROVAL. Approvers only record a decision here; the Workflow is
 * resumed by the original runner's own client with its freshly authenticated identity, so an
 * approver never lends their authority and no runner authority is restored from stored data.
 */
@Service
public class RuntimeWorkflowApprovalService {

    public static final String PENDING_CODE = "WORKFLOW_APPROVAL_PENDING";
    static final int SCAN_LIMIT = 500;
    /** After a decision the runner's client has this long to pick it up and continue. */
    static final long DECIDED_PICKUP_HOURS = 24;
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final RuntimeInteractionSessionMapper sessionMapper;
    private final RuntimeWorkflowInteractionSessionService sessionService;

    public RuntimeWorkflowApprovalService(RuntimeInteractionSessionMapper sessionMapper,
                                          RuntimeWorkflowInteractionSessionService sessionService) {
        this.sessionMapper = sessionMapper;
        this.sessionService = sessionService;
    }

    /** Platform approver attested by Control's signed internal call. */
    public record Approver(String runtimeUserId, String username, String displayName, List<String> roles,
                           List<String> projectScope, boolean admin) {
        public Approver {
            roles = roles == null ? List.of() : roles.stream().filter(StringUtils::hasText)
                    .map(role -> role.trim().toUpperCase(Locale.ROOT)).toList();
            projectScope = projectScope == null ? List.of() : List.copyOf(projectScope);
        }

        String label() {
            return StringUtils.hasText(displayName) ? displayName : username;
        }

        boolean coversProject(String projectCode) {
            return projectScope.contains("*") || (projectCode != null && projectScope.contains(projectCode));
        }
    }

    public static final class ApprovalException extends RuntimeException {
        private final int status;
        private final String code;

        ApprovalException(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }

        public int status() { return status; }
        public String code() { return code; }
    }

    /**
     * Turns a waiting HUMAN_APPROVAL with named approvers into an ASSIGNED_APPROVAL wait. Requests
     * without an assignment are returned unchanged.
     */
    public static RuntimeWorkflowInteractionSessionService.CreateRequest assign(
            RuntimeWorkflowInteractionSessionService.CreateRequest base,
            Map<String, Object> resultMetadata,
            Map<String, Object> runner,
            Map<String, Object> labels) {
        if (base == null || resultMetadata == null) return base;
        WorkflowApprovalAssignment assignment =
                WorkflowApprovalAssignment.fromMap(resultMetadata.get(WorkflowApprovalAssignment.METADATA_KEY));
        if (assignment == null || assignment.isEmpty()) return base;
        Map<String, Object> continuation = base.continuation() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(base.continuation());
        continuation.put(WorkflowApprovalAssignment.METADATA_KEY, assignment.toMap());
        continuation.remove(WorkflowApprovalAssignment.DECISION_KEY);
        Map<String, Object> runnerView = new LinkedHashMap<>();
        if (runner != null) runner.forEach((key, value) -> { if (value != null) runnerView.put(key, value); });
        continuation.put(WorkflowApprovalAssignment.RUNNER_KEY, runnerView);
        if (labels != null) labels.forEach((key, value) -> { if (value != null) continuation.putIfAbsent(key, value); });
        Integer ttl = base.ttlSeconds() == null || base.ttlSeconds() <= 0
                ? WorkflowApprovalAssignment.DEFAULT_TTL_SECONDS
                : Math.min(base.ttlSeconds(), WorkflowApprovalAssignment.MAX_TTL_SECONDS);
        return new RuntimeWorkflowInteractionSessionService.CreateRequest(base.interactionId(), base.sourceType(),
                base.runId(), base.traceId(), base.workflowId(), base.workflowVersionId(), base.graphSpecSnapshotJson(),
                base.nodeId(), WorkflowApprovalAssignment.INTERACTION_TYPE, base.resumeCheckpoint(),
                pendingUiRequest(base.uiRequest(), assignment, null), continuation, base.appId(), base.tenantId(),
                base.sessionId(), base.userId(), ttl);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> pendingUiRequest(Object raw, WorkflowApprovalAssignment assignment,
                                                Map<String, Object> decision) {
        Map<String, Object> ui = raw instanceof Map<?, ?> map
                ? new LinkedHashMap<>((Map<String, Object>) map) : new LinkedHashMap<>();
        ui.put("component", WorkflowApprovalAssignment.COMPONENT);
        ui.remove("fields");
        ui.put("actions", List.of(Map.of("id", "cancel", "action", "cancel", "label", "撤回", "style", "secondary")));
        Map<String, Object> extension = ui.get("extension") instanceof Map<?, ?> ext
                ? new LinkedHashMap<>((Map<String, Object>) ext) : new LinkedHashMap<>();
        Map<String, Object> approval = new LinkedHashMap<>();
        approval.put("assignees", assignment.assigneeLabel());
        approval.put("status", decision == null ? "PENDING" : decision.get("decision"));
        if (decision != null) {
            approval.put("approverName", decision.get("approverName"));
            if (decision.get("comment") != null) approval.put("comment", decision.get("comment"));
            approval.put("decidedAt", decision.get("decidedAt"));
        }
        extension.put("assignedApproval", approval);
        ui.put("extension", extension);
        return ui;
    }

    /** Pending items this approver may decide, or every pending item for a platform admin asking for all. */
    public List<Map<String, Object>> listPending(Approver approver, boolean all, int limit) {
        List<RuntimeInteractionSessionEntity> rows = sessionMapper.selectList(baseQuery()
                .eq(RuntimeInteractionSessionEntity::getStatus, "WAITING_USER")
                .gt(RuntimeInteractionSessionEntity::getExpiresAt, LocalDateTime.now())
                .orderByDesc(RuntimeInteractionSessionEntity::getCreateTime)
                .last("LIMIT " + SCAN_LIMIT));
        List<Map<String, Object>> views = new ArrayList<>();
        for (RuntimeInteractionSessionEntity row : rows) {
            Map<String, Object> continuation = sessionService.readMap(row.getContinuationJson());
            if (continuation.get(WorkflowApprovalAssignment.DECISION_KEY) instanceof Map<?, ?>) continue;
            boolean mine = canDecide(row, continuation, approver);
            if (!mine && !(all && approver.admin())) continue;
            views.add(view(row, continuation, approver));
            if (views.size() >= clamp(limit)) break;
        }
        return views;
    }

    /** Items this approver already decided, newest first. */
    public List<Map<String, Object>> listDecided(Approver approver, int limit) {
        List<RuntimeInteractionSessionEntity> rows = sessionMapper.selectList(baseQuery()
                .orderByDesc(RuntimeInteractionSessionEntity::getUpdateTime)
                .last("LIMIT " + SCAN_LIMIT));
        List<Map<String, Object>> views = new ArrayList<>();
        for (RuntimeInteractionSessionEntity row : rows) {
            Map<String, Object> continuation = sessionService.readMap(row.getContinuationJson());
            if (!(continuation.get(WorkflowApprovalAssignment.DECISION_KEY) instanceof Map<?, ?> decision)) continue;
            if (approver.username() == null
                    || !approver.username().equalsIgnoreCase(String.valueOf(decision.get("approverUsername")))) continue;
            views.add(view(row, continuation, approver));
            if (views.size() >= clamp(limit)) break;
        }
        return views;
    }

    public long countPending(Approver approver) {
        return listPending(approver, false, SCAN_LIMIT).size();
    }

    @Transactional
    public Map<String, Object> decide(String interactionId, Approver approver, String rawDecision, String comment) {
        String decision = normalizeDecision(rawDecision);
        if (decision == null) throw new ApprovalException(400, "WORKFLOW_APPROVAL_DECISION_INVALID", "请选择批准或拒绝");
        String trimmedComment = comment == null ? null : comment.trim();
        if (trimmedComment != null && trimmedComment.length() > 500) {
            throw new ApprovalException(400, "WORKFLOW_APPROVAL_COMMENT_TOO_LONG", "审批意见最多 500 字");
        }
        if (!StringUtils.hasText(interactionId)) throw notFound();
        RuntimeInteractionSessionEntity row = sessionMapper.selectForUpdate(interactionId.trim());
        if (row == null || !WorkflowApprovalAssignment.INTERACTION_TYPE.equals(row.getInteractionType())
                || !"WORKFLOW".equals(row.getSourceType())) {
            throw notFound();
        }
        Map<String, Object> continuation = sessionService.readMap(row.getContinuationJson());
        boolean mine = canDecide(row, continuation, approver);
        if (!mine && !approver.admin()) {
            throw new ApprovalException(403, "WORKFLOW_APPROVAL_FORBIDDEN", "这条审批没有指派给你");
        }
        if (continuation.get(WorkflowApprovalAssignment.DECISION_KEY) instanceof Map<?, ?> existing) {
            throw new ApprovalException(409, "WORKFLOW_APPROVAL_ALREADY_DECIDED",
                    "已被 " + existing.get("approverName") + " 处理");
        }
        if (!"WAITING_USER".equals(row.getStatus())) {
            throw new ApprovalException(409, "WORKFLOW_APPROVAL_CLOSED", "这次运行已经结束，不能再审批");
        }
        LocalDateTime now = LocalDateTime.now();
        if (row.getExpiresAt() != null && !row.getExpiresAt().isAfter(now)) {
            throw new ApprovalException(409, "WORKFLOW_APPROVAL_EXPIRED", "审批已超时");
        }
        WorkflowApprovalAssignment assignment =
                WorkflowApprovalAssignment.fromMap(continuation.get(WorkflowApprovalAssignment.METADATA_KEY));
        if (assignment != null && !assignment.allowRunner() && isRunner(row, approver)) {
            throw new ApprovalException(403, "WORKFLOW_APPROVAL_SELF_FORBIDDEN", "不能审批自己发起的运行");
        }
        Map<String, Object> decided = new LinkedHashMap<>();
        decided.put("decision", decision);
        decided.put("approverUsername", approver.username());
        decided.put("approverName", approver.label());
        decided.put("approverUserId", approver.runtimeUserId());
        if (StringUtils.hasText(trimmedComment)) decided.put("comment", trimmedComment);
        decided.put("decidedAt", ISO.format(now));
        decided.put("onBehalf", !mine);
        continuation.put(WorkflowApprovalAssignment.DECISION_KEY, decided);
        Map<String, Object> ui = pendingUiRequest(sessionService.readMap(row.getUiRequestJson()),
                assignment == null ? new WorkflowApprovalAssignment(List.of(), List.of(), false) : assignment, decided);
        LocalDateTime pickup = now.plusHours(DECIDED_PICKUP_HOURS);
        LocalDateTime expiresAt = row.getExpiresAt() == null || row.getExpiresAt().isBefore(pickup)
                ? pickup : row.getExpiresAt();
        int revision = row.getRevision() == null ? 0 : row.getRevision();
        int updated = sessionMapper.update(null, Wrappers.<RuntimeInteractionSessionEntity>lambdaUpdate()
                .eq(RuntimeInteractionSessionEntity::getId, row.getId())
                .eq(RuntimeInteractionSessionEntity::getStatus, "WAITING_USER")
                .eq(RuntimeInteractionSessionEntity::getRevision, revision)
                .set(RuntimeInteractionSessionEntity::getContinuationJson, sessionService.writeJson(continuation))
                .set(RuntimeInteractionSessionEntity::getUiRequestJson, sessionService.writeJson(ui))
                .set(RuntimeInteractionSessionEntity::getExpiresAt, expiresAt)
                .set(RuntimeInteractionSessionEntity::getRevision, revision + 1)
                .set(RuntimeInteractionSessionEntity::getUpdateTime, now));
        if (updated != 1) {
            throw new ApprovalException(409, "WORKFLOW_APPROVAL_CONFLICT", "这条审批刚刚发生了变化，请刷新后再试");
        }
        Map<String, Object> event = new LinkedHashMap<>(decided);
        event.remove("approverUserId");
        sessionService.writeEvent(row.getId(), "APPROVAL_DECIDED", event, approver.runtimeUserId());
        row.setContinuationJson(sessionService.writeJson(continuation));
        row.setUiRequestJson(sessionService.writeJson(ui));
        row.setExpiresAt(expiresAt);
        return view(row, continuation, approver);
    }

    /**
     * Runner-side status for the waiting card. Only the conversation that started the run may read it;
     * Control has already verified that the caller owns {@code sessionId}.
     */
    public Map<String, Object> runnerStatus(String interactionId, String sessionId, String userId, String appId) {
        if (!StringUtils.hasText(interactionId) || !StringUtils.hasText(sessionId)) throw notFound();
        RuntimeInteractionSessionEntity row = sessionMapper.selectById(interactionId.trim());
        if (row == null || !WorkflowApprovalAssignment.INTERACTION_TYPE.equals(row.getInteractionType())
                || !sessionId.trim().equals(row.getSessionId())
                || (StringUtils.hasText(userId) && !userId.trim().equals(row.getUserId()))
                || (StringUtils.hasText(appId) && StringUtils.hasText(row.getAppId()) && !appId.trim().equals(row.getAppId()))) {
            throw notFound();
        }
        Map<String, Object> continuation = sessionService.readMap(row.getContinuationJson());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("interactionId", row.getId());
        body.put("status", approvalStatus(row, continuation));
        body.put("sessionStatus", row.getStatus());
        if (row.getExpiresAt() != null) body.put("expiresAt", ISO.format(row.getExpiresAt()));
        WorkflowApprovalAssignment assignment =
                WorkflowApprovalAssignment.fromMap(continuation.get(WorkflowApprovalAssignment.METADATA_KEY));
        if (assignment != null) body.put("assignees", assignment.assigneeLabel());
        if (continuation.get(WorkflowApprovalAssignment.DECISION_KEY) instanceof Map<?, ?> decision) {
            body.put("approverName", decision.get("approverName"));
            if (decision.get("comment") != null) body.put("comment", decision.get("comment"));
            body.put("decidedAt", decision.get("decidedAt"));
        }
        return body;
    }

    /**
     * The decision the runner's resume must apply, as {action, values}; null while still pending.
     * The runner's own submitted action is never trusted for an ASSIGNED_APPROVAL session.
     */
    public static Map<String, Object> decisionForResume(Map<String, Object> continuation) {
        if (continuation == null || !(continuation.get(WorkflowApprovalAssignment.DECISION_KEY) instanceof Map<?, ?> decision)) {
            return null;
        }
        String action = "approved".equals(decision.get("decision")) ? "confirm" : "reject";
        Map<String, Object> values = new LinkedHashMap<>();
        for (String key : List.of("comment", "approverUsername", "approverName", "decidedAt")) {
            if (decision.get(key) != null) values.put(key, decision.get(key));
        }
        Map<String, Object> resolved = new LinkedHashMap<>();
        resolved.put("action", action);
        resolved.put("values", values);
        return resolved;
    }

    private LambdaQueryWrapper<RuntimeInteractionSessionEntity> baseQuery() {
        return Wrappers.<RuntimeInteractionSessionEntity>lambdaQuery()
                .eq(RuntimeInteractionSessionEntity::getSourceType, "WORKFLOW")
                .eq(RuntimeInteractionSessionEntity::getInteractionType, WorkflowApprovalAssignment.INTERACTION_TYPE);
    }

    private boolean canDecide(RuntimeInteractionSessionEntity row, Map<String, Object> continuation, Approver approver) {
        WorkflowApprovalAssignment assignment =
                WorkflowApprovalAssignment.fromMap(continuation.get(WorkflowApprovalAssignment.METADATA_KEY));
        if (assignment == null) return false;
        if (assignment.assignedTo(approver.username(), List.of())) return true;
        // Role assignments only reach approvers whose role grant covers this project.
        return assignment.assignedTo(null, approver.roles()) && approver.coversProject(row.getAppId());
    }

    private static boolean isRunner(RuntimeInteractionSessionEntity row, Approver approver) {
        return StringUtils.hasText(approver.runtimeUserId()) && approver.runtimeUserId().equals(row.getUserId());
    }

    private Map<String, Object> view(RuntimeInteractionSessionEntity row, Map<String, Object> continuation,
                                     Approver approver) {
        Map<String, Object> ui = sessionService.readMap(row.getUiRequestJson());
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("interactionId", row.getId());
        view.put("title", firstText(ui.get("title"), "人工确认"));
        view.put("message", ui.get("message"));
        view.put("workflowId", row.getWorkflowId());
        view.put("workflowName", firstText(continuation.get("workflowName"), continuation.get("toolName"), row.getWorkflowId()));
        view.put("agentName", continuation.get("agentName"));
        view.put("projectCode", row.getAppId());
        view.put("runId", row.getRunId());
        view.put("traceId", row.getTraceId());
        Map<String, Object> runner = continuation.get(WorkflowApprovalAssignment.RUNNER_KEY) instanceof Map<?, ?> raw
                ? sessionService.readMap(sessionService.writeJson(raw)) : Map.of();
        view.put("runnerName", firstText(runner.get("userName"), row.getUserId()));
        view.put("runnerUserId", row.getUserId());
        WorkflowApprovalAssignment assignment =
                WorkflowApprovalAssignment.fromMap(continuation.get(WorkflowApprovalAssignment.METADATA_KEY));
        view.put("assignees", assignment == null ? null : assignment.assigneeLabel());
        view.put("status", approvalStatus(row, continuation));
        view.put("sessionStatus", row.getStatus());
        view.put("createdAt", row.getCreateTime() == null ? null : ISO.format(row.getCreateTime()));
        view.put("expiresAt", row.getExpiresAt() == null ? null : ISO.format(row.getExpiresAt()));
        view.put("assignedToMe", canDecide(row, continuation, approver));
        view.put("selfRun", isRunner(row, approver) && (assignment == null || !assignment.allowRunner()));
        if (continuation.get(WorkflowApprovalAssignment.DECISION_KEY) instanceof Map<?, ?> decision) {
            Map<String, Object> decided = new LinkedHashMap<>();
            for (String key : List.of("decision", "approverName", "comment", "decidedAt", "onBehalf")) {
                if (decision.get(key) != null) decided.put(key, decision.get(key));
            }
            view.put("decision", decided);
        }
        return view;
    }

    /** PENDING / APPROVED / REJECTED (waiting for the runner) / CONTINUED / WITHDRAWN / EXPIRED / FAILED. */
    static String approvalStatus(RuntimeInteractionSessionEntity row, Map<String, Object> continuation) {
        Object decision = continuation.get(WorkflowApprovalAssignment.DECISION_KEY) instanceof Map<?, ?> map
                ? map.get("decision") : null;
        String status = row.getStatus() == null ? "" : row.getStatus();
        switch (status) {
            case "WAITING_USER", "RESUMING" -> {
                if (decision == null) {
                    return row.getExpiresAt() != null && !row.getExpiresAt().isAfter(LocalDateTime.now())
                            ? "EXPIRED" : "PENDING";
                }
                return "approved".equals(decision) ? "APPROVED" : "REJECTED";
            }
            case "COMPLETED" -> { return decision == null ? "COMPLETED" : "approved".equals(decision) ? "CONTINUED_APPROVED" : "CONTINUED_REJECTED"; }
            case "CANCELLED" -> { return "WITHDRAWN"; }
            case "EXPIRED" -> { return "EXPIRED"; }
            default -> { return status.isEmpty() ? "UNKNOWN" : status; }
        }
    }

    private static String normalizeDecision(String raw) {
        if (raw == null) return null;
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (Set.of("approve", "approved", "confirm").contains(value)) return "approved";
        if (Set.of("reject", "rejected", "deny").contains(value)) return "rejected";
        return null;
    }

    private static ApprovalException notFound() {
        return new ApprovalException(404, "WORKFLOW_APPROVAL_NOT_FOUND", "审批不存在");
    }

    private static int clamp(int limit) {
        return Math.max(1, Math.min(limit <= 0 ? 50 : limit, 200));
    }

    private static String firstText(Object... values) {
        for (Object value : values) {
            if (value != null && StringUtils.hasText(String.valueOf(value))) return String.valueOf(value).trim();
        }
        return null;
    }

    static List<String> stringList(Object raw) {
        if (!(raw instanceof Collection<?> values)) return List.of();
        return values.stream().filter(value -> value != null && StringUtils.hasText(String.valueOf(value)))
                .map(value -> String.valueOf(value).trim()).toList();
    }
}
