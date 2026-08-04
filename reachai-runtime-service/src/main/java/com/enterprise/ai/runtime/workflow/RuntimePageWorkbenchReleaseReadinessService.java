package com.enterprise.ai.runtime.workflow;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowReleaseValidationResult.Item;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowResourceBindingService.BindingView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class RuntimePageWorkbenchReleaseReadinessService {

    public static final String PASS = "PASS";
    public static final String FAIL = "FAIL";
    public static final String VALIDATED_DRAFT = "VALIDATED_DRAFT";
    public static final String PUBLISHED = "PUBLISHED";

    private final RuntimeWorkflowDefinitionMapper workflowMapper;
    private final RuntimeWorkflowVersionMapper versionMapper;
    private final RuntimeWorkflowResourceBindingService bindingService;
    private final RuntimeWorkflowReleaseValidationService validationService;

    public ReleaseReadinessView evaluate(
            String projectCode,
            String pageKey,
            String workflowId,
            String requestedVersion) {
        String expectedProjectCode = requireText(projectCode, "projectCode");
        String expectedPageKey = requireText(pageKey, "pageKey");
        String expectedWorkflowId = requireText(workflowId, "workflowId");
        String expectedVersion = requireText(
                requestedVersion,
                "workflowVersion");
        RuntimeWorkflowDefinitionEntity workflow =
                workflowMapper.selectById(expectedWorkflowId);
        if (workflow == null) {
            return failed(
                    "WORKFLOW_NOT_FOUND",
                    "目标 Workflow 不存在",
                    expectedProjectCode,
                    expectedPageKey,
                    expectedWorkflowId,
                    expectedVersion,
                    null,
                    null,
                    null,
                    List.of(),
                    List.of());
        }
        if (!expectedProjectCode.equalsIgnoreCase(workflow.getProjectCode())) {
            return failed(
                    "WORKFLOW_PROJECT_MISMATCH",
                    "目标 Workflow 不属于当前 ReachAI 项目",
                    expectedProjectCode,
                    expectedPageKey,
                    expectedWorkflowId,
                    expectedVersion,
                    workflow,
                    null,
                    null,
                    List.of(),
                    List.of());
        }
        if (!WorkflowSemanticValues.KIND_PAGE_ASSISTANT.equals(
                workflow.getWorkflowKind())) {
            return failed(
                    "WORKFLOW_KIND_MISMATCH",
                    "目标 Workflow 不是 PAGE_ASSISTANT",
                    expectedProjectCode,
                    expectedPageKey,
                    expectedWorkflowId,
                    expectedVersion,
                    workflow,
                    null,
                    null,
                    List.of(),
                    List.of());
        }
        if (!"DRAFT".equalsIgnoreCase(workflow.getStatus())
                && !"ACTIVE".equalsIgnoreCase(workflow.getStatus())) {
            return failed(
                    "WORKFLOW_STATUS_INVALID",
                    "目标 Workflow 当前不可发布",
                    expectedProjectCode,
                    expectedPageKey,
                    expectedWorkflowId,
                    expectedVersion,
                    workflow,
                    null,
                    null,
                    List.of(),
                    List.of());
        }

        List<BindingView> targetPages = bindingService.list(expectedWorkflowId)
                .stream()
                .filter(binding -> RuntimeWorkflowResourceBindingService.RESOURCE_PAGE
                        .equals(binding.resourceType()))
                .filter(binding -> RuntimeWorkflowResourceBindingService.ROLE_TARGET
                        .equals(binding.bindingRole()))
                .toList();
        if (targetPages.size() != 1
                || !expectedPageKey.equals(targetPages.get(0).resourceKey())) {
            return failed(
                    "WORKFLOW_PAGE_MISMATCH",
                    "目标 Workflow 没有唯一绑定当前页面",
                    expectedProjectCode,
                    expectedPageKey,
                    expectedWorkflowId,
                    expectedVersion,
                    workflow,
                    null,
                    null,
                    List.of(),
                    List.of());
        }

        RuntimeWorkflowReleaseValidationResult validation =
                validationService.validate(workflow);
        if (!validation.valid()) {
            return failed(
                    "RELEASE_VALIDATION_FAILED",
                    "ReachAI Workflow 发布校验未通过",
                    expectedProjectCode,
                    expectedPageKey,
                    expectedWorkflowId,
                    expectedVersion,
                    workflow,
                    null,
                    false,
                    validation.errors(),
                    validation.warnings());
        }

        RuntimeWorkflowVersionEntity requested = versionMapper.selectOne(
                Wrappers.<RuntimeWorkflowVersionEntity>lambdaQuery()
                        .eq(
                                RuntimeWorkflowVersionEntity::getWorkflowId,
                                expectedWorkflowId)
                        .eq(
                                RuntimeWorkflowVersionEntity::getVersion,
                                expectedVersion)
                        .last("LIMIT 1"));
        if (requested != null
                && !"ACTIVE".equalsIgnoreCase(requested.getStatus())) {
            return failed(
                    "WORKFLOW_VERSION_ALREADY_USED",
                    "目标 Workflow 版本号已存在，但不是当前 ACTIVE 版本",
                    expectedProjectCode,
                    expectedPageKey,
                    expectedWorkflowId,
                    expectedVersion,
                    workflow,
                    requested,
                    true,
                    validation.errors(),
                    validation.warnings());
        }
        if (requested != null
                && !"ACTIVE".equalsIgnoreCase(workflow.getStatus())) {
            return failed(
                    "PUBLISHED_WORKFLOW_NOT_ACTIVE",
                    "目标版本已发布，但 Workflow 不是 ACTIVE 状态",
                    expectedProjectCode,
                    expectedPageKey,
                    expectedWorkflowId,
                    expectedVersion,
                    workflow,
                    requested,
                    true,
                    validation.errors(),
                    validation.warnings());
        }

        RuntimeWorkflowVersionEntity active = requested != null
                ? requested
                : versionMapper.listActive(expectedWorkflowId).stream()
                .findFirst()
                .orElse(null);
        String releaseState = requested == null
                ? VALIDATED_DRAFT
                : PUBLISHED;
        String message = requested == null
                ? "ReachAI 已确认 Workflow、页面绑定和发布校验均通过，目标版本号可用"
                : "ReachAI 已确认目标 Workflow 版本真实发布且处于 ACTIVE 状态";
        return new ReleaseReadinessView(
                PASS,
                "PRE_RELEASE_READY",
                message,
                expectedProjectCode,
                expectedPageKey,
                expectedWorkflowId,
                expectedVersion,
                workflow.getWorkflowKind(),
                workflow.getStatus(),
                releaseState,
                true,
                active == null ? null : active.getId(),
                active == null ? null : active.getVersion(),
                validation.errors(),
                validation.warnings(),
                LocalDateTime.now());
    }

    private ReleaseReadinessView failed(
            String code,
            String message,
            String projectCode,
            String pageKey,
            String workflowId,
            String requestedVersion,
            RuntimeWorkflowDefinitionEntity workflow,
            RuntimeWorkflowVersionEntity published,
            Boolean releaseValid,
            List<Item> errors,
            List<Item> warnings) {
        return new ReleaseReadinessView(
                FAIL,
                code,
                message,
                projectCode,
                pageKey,
                workflowId,
                requestedVersion,
                workflow == null ? null : workflow.getWorkflowKind(),
                workflow == null ? null : workflow.getStatus(),
                null,
                releaseValid,
                published == null ? null : published.getId(),
                published == null ? null : published.getVersion(),
                errors,
                warnings,
                LocalDateTime.now());
    }

    private static String requireText(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    public record ReleaseReadinessView(
            String status,
            String code,
            String message,
            String projectCode,
            String pageKey,
            String workflowId,
            String requestedVersion,
            String workflowKind,
            String workflowStatus,
            String releaseState,
            Boolean releaseValid,
            Long publishedVersionId,
            String publishedVersion,
            List<Item> errors,
            List<Item> warnings,
            LocalDateTime checkedAt) {
    }
}
