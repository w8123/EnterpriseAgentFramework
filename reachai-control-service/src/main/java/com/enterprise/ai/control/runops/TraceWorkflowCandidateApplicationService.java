package com.enterprise.ai.control.runops;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.control.aicoding.application.AiCodingTaskApplicationService;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.CreateTaskCommand;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskTargetCommand;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskView;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskEntity;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskMapper;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskTargetEntity;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskTargetMapper;
import com.enterprise.ai.control.client.capability.CapabilityProjectOnboardingClient;
import com.enterprise.ai.control.runops.TraceWorkflowCandidateEligibility.EligibilityView;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class TraceWorkflowCandidateApplicationService {

    private static final Set<String> REUSABLE_STATUSES = Set.of(
            "READY", "RUNNING", "WAITING_USER", "RESULT_SUBMITTED",
            "RESULT_APPLIED", "ACCEPTANCE_READY");

    private final TraceWorkflowCandidateEligibility eligibilityService;
    private final CapabilityProjectOnboardingClient capabilityClient;
    private final AiCodingTaskApplicationService taskService;
    private final AiCodingTaskMapper taskMapper;
    private final AiCodingTaskTargetMapper targetMapper;
    private final ObjectMapper objectMapper;

    public EligibilityView eligibility(String traceId) {
        return eligibilityService.evaluate(traceId);
    }

    @Transactional
    public CandidateTaskView createOrReuse(
            String traceId,
            CreateCandidateTaskRequest request) {
        EligibilityView eligibility = eligibilityService.evaluate(traceId);
        if (!eligibility.eligible()) {
            throw new IllegalArgumentException(
                    "selected trace is not eligible: "
                            + String.join("; ", eligibility.blockers()));
        }
        String executorProvider = request == null
                ? "CODEX"
                : firstText(request.executorProvider(), "CODEX");
        TaskView existing = findReusable(
                eligibility.traceId(), executorProvider);
        if (existing != null) {
            return new CandidateTaskView(
                    "reachai.runops.trace-workflow-candidate-task.v1",
                    false,
                    eligibility,
                    existing);
        }

        Map<String, Object> project = capabilityClient.getProjectByCode(
                eligibility.projectCode());
        Long projectId = projectId(project);
        if (projectId == null) {
            throw new IllegalArgumentException(
                    "selected trace project is not registered in Capability service");
        }
        String actualProjectCode = text(project == null
                ? null
                : project.get("projectCode"));
        if (StringUtils.hasText(actualProjectCode)
                && !eligibility.projectCode().equalsIgnoreCase(
                actualProjectCode.trim())) {
            throw new IllegalArgumentException(
                    "Capability project identity does not match the selected trace");
        }

        ObjectNode snapshot = objectMapper.createObjectNode();
        snapshot.put("schema", TraceWorkflowCandidateEligibility.ELIGIBILITY_SCHEMA);
        snapshot.put("traceId", eligibility.traceId());
        snapshot.put("projectCode", eligibility.projectCode());
        snapshot.put("sourceWorkflowId", eligibility.sourceWorkflowId());
        snapshot.put("sourceWorkflowVersionId",
                eligibility.sourceWorkflowVersionId());
        snapshot.put("sourceWorkflowVersion",
                eligibility.sourceWorkflowVersion());
        snapshot.set("eligibilityFacts", eligibility.facts());

        TaskView created = taskService.create(new CreateTaskCommand(
                projectId,
                eligibility.projectCode(),
                TraceWorkflowCandidateTaskProvider.TASK_KIND,
                executorProvider,
                "从运行轨迹生成 Workflow 候选",
                "基于追踪 " + eligibility.traceId()
                        + " 的精确发布版本与实际成功路径，产出一个经 Runtime 校验的 DRAFT Workflow；"
                        + "不得自动发布、绑定 Agent 或扩展认证授权范围。",
                request == null ? null : request.createdBy(),
                List.of(new TaskTargetCommand(
                        TraceWorkflowCandidateEligibility.PRIMARY_TARGET_TYPE,
                        eligibility.traceId(),
                        "PRIMARY",
                        "READ_WRITE",
                        snapshot))));
        return new CandidateTaskView(
                "reachai.runops.trace-workflow-candidate-task.v1",
                true,
                eligibility,
                created);
    }

    private TaskView findReusable(String traceId, String executorProvider) {
        List<AiCodingTaskTargetEntity> targets = targetMapper.selectList(
                Wrappers.<AiCodingTaskTargetEntity>lambdaQuery()
                        .eq(AiCodingTaskTargetEntity::getTargetType,
                                TraceWorkflowCandidateEligibility.PRIMARY_TARGET_TYPE)
                        .eq(AiCodingTaskTargetEntity::getTargetKey, traceId)
                        .eq(AiCodingTaskTargetEntity::getTargetRole, "PRIMARY")
                        .orderByDesc(AiCodingTaskTargetEntity::getCreatedAt));
        for (AiCodingTaskTargetEntity target : targets) {
            AiCodingTaskEntity task = taskMapper.selectById(target.getTaskId());
            if (task == null
                    || !TraceWorkflowCandidateTaskProvider.TASK_KIND.equals(
                    task.getTaskKind())
                    || !executorProvider.equalsIgnoreCase(
                    task.getExecutorProvider())
                    || !REUSABLE_STATUSES.contains(task.getExecutionStatus())) {
                continue;
            }
            return taskService.task(task.getTaskId());
        }
        return null;
    }

    private static Long projectId(Map<String, Object> project) {
        if (project == null) return null;
        Long id = longValue(project.get("projectId"));
        return id != null ? id : longValue(project.get("id"));
    }

    private static Long longValue(Object value) {
        if (value instanceof Number number) return number.longValue();
        try {
            return value == null ? null : Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String firstText(String first, String fallback) {
        return StringUtils.hasText(first) ? first.trim() : fallback;
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    public record CreateCandidateTaskRequest(
            String executorProvider,
            String createdBy) {
    }

    public record CandidateTaskView(
            String schema,
            boolean created,
            EligibilityView eligibility,
            TaskView task) {
    }
}
