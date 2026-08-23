package com.enterprise.ai.runtime.workflow;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.runtime.runops.RuntimeRunEntity;
import com.enterprise.ai.runtime.runops.RuntimeRunMapper;
import com.enterprise.ai.runtime.trace.RuntimeTraceSpanEntity;
import com.enterprise.ai.runtime.trace.RuntimeTraceSpanMapper;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowResourceBindingService.BindingView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Verifies that an exact post-task Embed trace successfully executed the
 * published Workflow/version selected by a page-workbench acceptance task.
 * Capability and Page Action coverage is derived from the node types actually
 * observed in the exact trace, never merely from the published graph.
 */
@Service
@RequiredArgsConstructor
public class RuntimePageWorkbenchExecutionReadinessService {

    private static final String PASS = "PASS";
    private static final String FAIL = "FAIL";

    private final RuntimeRunMapper runMapper;
    private final RuntimeTraceSpanMapper spanMapper;
    private final RuntimeWorkflowDefinitionMapper workflowMapper;
    private final RuntimeWorkflowVersionMapper versionMapper;
    private final RuntimeWorkflowResourceBindingService bindingService;
    private final ObjectMapper objectMapper;

    public ExecutionReadinessView evaluate(
            String projectCode,
            String pageKey,
            String sessionId,
            String pageInstanceId,
            String traceId,
            String workflowId,
            Long workflowVersionId,
            String workflowVersion) {
        String expectedProjectCode = requireText(projectCode, "projectCode");
        String expectedPageKey = requireText(pageKey, "pageKey");
        String expectedSessionId = requireText(sessionId, "sessionId");
        String expectedPageInstanceId = requireText(
                pageInstanceId,
                "pageInstanceId");
        String expectedTraceId = requireText(traceId, "traceId");
        String expectedWorkflowId = requireText(workflowId, "workflowId");
        if (workflowVersionId == null || workflowVersionId <= 0) {
            throw new IllegalArgumentException(
                    "workflowVersionId must be positive");
        }
        String expectedWorkflowVersion = requireText(
                workflowVersion,
                "workflowVersion");

        RuntimeWorkflowDefinitionEntity workflow =
                workflowMapper.selectById(expectedWorkflowId);
        if (workflow == null) {
            return failed(
                    "WORKFLOW_NOT_FOUND",
                    "目标 Workflow 不存在",
                    expectedProjectCode,
                    expectedPageKey,
                    expectedSessionId,
                    expectedPageInstanceId,
                    expectedTraceId,
                    expectedWorkflowId,
                    workflowVersionId,
                    expectedWorkflowVersion,
                    null,
                    NodeUsage.none());
        }
        if (!expectedProjectCode.equalsIgnoreCase(
                workflow.getProjectCode())) {
            return failed(
                    "WORKFLOW_PROJECT_MISMATCH",
                    "目标 Workflow 不属于当前 ReachAI 项目",
                    expectedProjectCode,
                    expectedPageKey,
                    expectedSessionId,
                    expectedPageInstanceId,
                    expectedTraceId,
                    expectedWorkflowId,
                    workflowVersionId,
                    expectedWorkflowVersion,
                    null,
                    NodeUsage.none());
        }
        if (!WorkflowSemanticValues.KIND_PAGE_ASSISTANT.equals(
                workflow.getWorkflowKind())
                || !"ACTIVE".equalsIgnoreCase(workflow.getStatus())) {
            return failed(
                    "WORKFLOW_NOT_ACTIVE_PAGE_ASSISTANT",
                    "目标 Workflow 不是已启用的 PAGE_ASSISTANT",
                    expectedProjectCode,
                    expectedPageKey,
                    expectedSessionId,
                    expectedPageInstanceId,
                    expectedTraceId,
                    expectedWorkflowId,
                    workflowVersionId,
                    expectedWorkflowVersion,
                    null,
                    NodeUsage.none());
        }
        List<BindingView> targetPages = bindingService.list(expectedWorkflowId)
                .stream()
                .filter(binding ->
                        RuntimeWorkflowResourceBindingService.RESOURCE_PAGE
                                .equals(binding.resourceType()))
                .filter(binding ->
                        RuntimeWorkflowResourceBindingService.ROLE_TARGET
                                .equals(binding.bindingRole()))
                .toList();
        if (targetPages.size() != 1
                || !expectedPageKey.equals(targetPages.get(0).resourceKey())) {
            return failed(
                    "WORKFLOW_PAGE_MISMATCH",
                    "目标 Workflow 没有唯一绑定当前页面",
                    expectedProjectCode,
                    expectedPageKey,
                    expectedSessionId,
                    expectedPageInstanceId,
                    expectedTraceId,
                    expectedWorkflowId,
                    workflowVersionId,
                    expectedWorkflowVersion,
                    null,
                    NodeUsage.none());
        }

        RuntimeWorkflowVersionEntity version =
                versionMapper.selectById(workflowVersionId);
        if (version == null
                || !expectedWorkflowId.equals(version.getWorkflowId())
                || !expectedWorkflowVersion.equals(version.getVersion())
                || !"ACTIVE".equalsIgnoreCase(version.getStatus())) {
            return failed(
                    "WORKFLOW_VERSION_MISMATCH",
                    "目标 Workflow 版本不存在、已退役或与验收任务不一致",
                    expectedProjectCode,
                    expectedPageKey,
                    expectedSessionId,
                    expectedPageInstanceId,
                    expectedTraceId,
                    expectedWorkflowId,
                    workflowVersionId,
                    expectedWorkflowVersion,
                    null,
                    NodeUsage.none());
        }
        NodeUsage nodeUsage = nodeUsage(version);

        RuntimeRunEntity run = runMapper.selectOne(
                Wrappers.<RuntimeRunEntity>lambdaQuery()
                        .eq(RuntimeRunEntity::getTraceId, expectedTraceId)
                        .last("LIMIT 1"));
        if (run == null) {
            return failed(
                    "RUNTIME_RUN_NOT_FOUND",
                    "当前 Trace 没有对应的 Runtime Run",
                    expectedProjectCode,
                    expectedPageKey,
                    expectedSessionId,
                    expectedPageInstanceId,
                    expectedTraceId,
                    expectedWorkflowId,
                    workflowVersionId,
                    expectedWorkflowVersion,
                    null,
                    nodeUsage);
        }
        if (!expectedProjectCode.equalsIgnoreCase(run.getProjectCode())
                || !expectedSessionId.equals(run.getSessionId())
                || !expectedPageInstanceId.equals(run.getPageInstanceId())
                || !"EMBED".equalsIgnoreCase(run.getEntryType())) {
            return failed(
                    "RUNTIME_RUN_IDENTITY_MISMATCH",
                    "当前 Trace 不属于本次页面会话",
                    expectedProjectCode,
                    expectedPageKey,
                    expectedSessionId,
                    expectedPageInstanceId,
                    expectedTraceId,
                    expectedWorkflowId,
                    workflowVersionId,
                    expectedWorkflowVersion,
                    run,
                    nodeUsage);
        }
        if (!"COMPLETED".equalsIgnoreCase(run.getStatus())) {
            return failed(
                    "RUNTIME_RUN_NOT_COMPLETED",
                    "当前页面 Runtime Run 尚未成功完成",
                    expectedProjectCode,
                    expectedPageKey,
                    expectedSessionId,
                    expectedPageInstanceId,
                    expectedTraceId,
                    expectedWorkflowId,
                    workflowVersionId,
                    expectedWorkflowVersion,
                    run,
                    nodeUsage);
        }

        List<RuntimeTraceSpanEntity> spans = defaultList(spanMapper.selectList(
                Wrappers.<RuntimeTraceSpanEntity>lambdaQuery()
                        .eq(
                                RuntimeTraceSpanEntity::getTraceId,
                                expectedTraceId)));
        boolean workflowObserved = spans.stream()
                .filter(span -> "WORKFLOW_TOOL".equals(span.getSpanType()))
                .filter(span -> isWorkflowCompletionStatus(span.getStatus()))
                .anyMatch(span -> exactWorkflow(
                        span,
                        expectedWorkflowId,
                        workflowVersionId,
                        expectedWorkflowVersion));
        if (!workflowObserved) {
            return failed(
                    "WORKFLOW_EXECUTION_NOT_OBSERVED",
                    "当前页面 Trace 未成功执行验收任务指定的 Workflow 版本",
                    expectedProjectCode,
                    expectedPageKey,
                    expectedSessionId,
                    expectedPageInstanceId,
                    expectedTraceId,
                    expectedWorkflowId,
                    workflowVersionId,
                    expectedWorkflowVersion,
                    run,
                    nodeUsage);
        }

        NodeObservation nodeObservation = observedNodes(spans);

        if (nodeUsage.structuredPresentationRequired()
                && !nodeObservation.structuredPresentationObserved()) {
            return failedAfterWorkflow(
                    "STRUCTURED_PRESENTATION_NOT_OBSERVED",
                    "当前页面 Trace 已执行目标 Workflow，但未执行 INTERACTION/PRESENT_OUTPUT 展示节点",
                    expectedProjectCode,
                    expectedPageKey,
                    expectedSessionId,
                    expectedPageInstanceId,
                    expectedTraceId,
                    expectedWorkflowId,
                    workflowVersionId,
                    expectedWorkflowVersion,
                    run,
                    nodeUsage,
                    nodeObservation);
        }

        return new ExecutionReadinessView(
                PASS,
                "PAGE_WORKFLOW_TRACE_READY",
                "ReachAI 已确认当前页面 Trace 成功执行指定 Workflow 版本",
                expectedProjectCode,
                expectedPageKey,
                expectedSessionId,
                expectedPageInstanceId,
                expectedTraceId,
                expectedWorkflowId,
                workflowVersionId,
                expectedWorkflowVersion,
                run.getStatus(),
                run.getEntryType(),
                true,
                nodeUsage.capabilityRequired(),
                nodeUsage.capabilityRequired() && nodeObservation.capabilityObserved(),
                nodeUsage.pageActionRequired(),
                nodeUsage.pageActionRequired() && nodeObservation.pageActionObserved(),
                nodeUsage.pageActionRequired()
                        && nodeObservation.pageActionBusinessTerminalObserved(),
                nodeUsage.structuredPresentationRequired(),
                nodeUsage.structuredPresentationRequired()
                        && nodeObservation.structuredPresentationObserved(),
                LocalDateTime.now());
    }

    private NodeUsage nodeUsage(RuntimeWorkflowVersionEntity version) {
        if (version == null
                || !StringUtils.hasText(version.getGraphSpecSnapshotJson())) {
            return NodeUsage.none();
        }
        try {
            JsonNode nodes = objectMapper.readTree(
                    version.getGraphSpecSnapshotJson()).path("nodes");
            if (!nodes.isArray()) {
                return NodeUsage.none();
            }
            boolean capability = false;
            boolean pageAction = false;
            boolean structuredPresentation = false;
            for (JsonNode node : nodes) {
                String type = node.path("type").asText("").trim().toUpperCase();
                capability = capability || "TOOL".equals(type);
                pageAction = pageAction || "PAGE_ACTION".equals(type);
                if ("INTERACTION".equals(type)) {
                    JsonNode config = node.path("config");
                    String interactionType = config.path("interactionType").asText("");
                    if (!StringUtils.hasText(interactionType)
                            && config.path("interactionConfig").isObject()) {
                        interactionType = config.path("interactionConfig")
                                .path("interactionType").asText("");
                    }
                    structuredPresentation = structuredPresentation
                            || "PRESENT_OUTPUT".equalsIgnoreCase(
                            interactionType.trim());
                }
            }
            return new NodeUsage(
                    capability,
                    pageAction,
                    structuredPresentation);
        } catch (Exception ignored) {
            return NodeUsage.none();
        }
    }

    private NodeObservation observedNodes(List<RuntimeTraceSpanEntity> spans) {
        boolean capabilityObserved = false;
        boolean pageActionObserved = false;
        boolean pageActionBusinessTerminalObserved = false;
        boolean structuredPresentationObserved = false;
        for (RuntimeTraceSpanEntity span : spans) {
            if (!"WORKFLOW_NODE".equals(span.getSpanType())) {
                continue;
            }
            String type = metadata(span).path("nodeType").asText("")
                    .trim().toUpperCase();
            if ("TOOL".equals(type)
                    && "SUCCESS".equalsIgnoreCase(span.getStatus())) {
                capabilityObserved = true;
            }
            if ("PAGE_ACTION".equals(type)
                    && isWorkflowCompletionStatus(span.getStatus())) {
                pageActionObserved = true;
                if ("BUSINESS_TERMINAL".equalsIgnoreCase(span.getStatus())) {
                    pageActionBusinessTerminalObserved = true;
                }
            }
            if ("INTERACTION".equals(type)
                    && "PRESENT_OUTPUT".equalsIgnoreCase(
                    metadata(span).path("interactionType").asText(""))
                    && "SUCCESS".equalsIgnoreCase(span.getStatus())) {
                structuredPresentationObserved = true;
            }
        }
        return new NodeObservation(
                capabilityObserved,
                pageActionObserved,
                pageActionBusinessTerminalObserved,
                structuredPresentationObserved);
    }

    private boolean isWorkflowCompletionStatus(String status) {
        return "SUCCESS".equalsIgnoreCase(status)
                || "BUSINESS_TERMINAL".equalsIgnoreCase(status);
    }

    private boolean exactWorkflow(
            RuntimeTraceSpanEntity span,
            String workflowId,
            Long workflowVersionId,
            String workflowVersion) {
        JsonNode metadata = metadata(span);
        return workflowId.equals(metadata.path("workflowId").asText())
                && workflowVersionId.equals(
                        metadata.path("workflowVersionId").asLong())
                && workflowVersion.equals(
                        metadata.path("workflowVersion").asText());
    }

    private JsonNode metadata(RuntimeTraceSpanEntity span) {
        try {
            return objectMapper.readTree(span.getMetadataJson());
        } catch (Exception ex) {
            return objectMapper.createObjectNode();
        }
    }

    private ExecutionReadinessView failed(
            String code,
            String message,
            String projectCode,
            String pageKey,
            String sessionId,
            String pageInstanceId,
            String traceId,
            String workflowId,
            Long workflowVersionId,
            String workflowVersion,
            RuntimeRunEntity run,
            NodeUsage nodeUsage) {
        return new ExecutionReadinessView(
                FAIL,
                code,
                message,
                projectCode,
                pageKey,
                sessionId,
                pageInstanceId,
                traceId,
                workflowId,
                workflowVersionId,
                workflowVersion,
                run == null ? null : run.getStatus(),
                run == null ? null : run.getEntryType(),
                false,
                nodeUsage.capabilityRequired(),
                false,
                nodeUsage.pageActionRequired(),
                false,
                false,
                nodeUsage.structuredPresentationRequired(),
                false,
                LocalDateTime.now());
    }

    private ExecutionReadinessView failedAfterWorkflow(
            String code,
            String message,
            String projectCode,
            String pageKey,
            String sessionId,
            String pageInstanceId,
            String traceId,
            String workflowId,
            Long workflowVersionId,
            String workflowVersion,
            RuntimeRunEntity run,
            NodeUsage nodeUsage,
            NodeObservation observed) {
        return new ExecutionReadinessView(
                FAIL,
                code,
                message,
                projectCode,
                pageKey,
                sessionId,
                pageInstanceId,
                traceId,
                workflowId,
                workflowVersionId,
                workflowVersion,
                run == null ? null : run.getStatus(),
                run == null ? null : run.getEntryType(),
                true,
                nodeUsage.capabilityRequired(),
                observed.capabilityObserved(),
                nodeUsage.pageActionRequired(),
                observed.pageActionObserved(),
                observed.pageActionBusinessTerminalObserved(),
                nodeUsage.structuredPresentationRequired(),
                observed.structuredPresentationObserved(),
                LocalDateTime.now());
    }

    private static String requireText(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private static <T> List<T> defaultList(List<T> value) {
        return value == null ? List.of() : value;
    }

    public record ExecutionReadinessView(
            String status,
            String code,
            String message,
            String projectCode,
            String pageKey,
            String sessionId,
            String pageInstanceId,
            String traceId,
            String workflowId,
            Long workflowVersionId,
            String workflowVersion,
            String runStatus,
            String entryType,
            boolean workflowObserved,
            boolean capabilityRequired,
            boolean capabilityObserved,
            boolean pageActionRequired,
            boolean pageActionObserved,
            boolean pageActionBusinessTerminalObserved,
            boolean structuredPresentationRequired,
            boolean structuredPresentationObserved,
            LocalDateTime checkedAt) {
    }

    private record NodeUsage(
            boolean capabilityRequired,
            boolean pageActionRequired,
            boolean structuredPresentationRequired) {

        private static NodeUsage none() {
            return new NodeUsage(false, false, false);
        }
    }

    private record NodeObservation(
            boolean capabilityObserved,
            boolean pageActionObserved,
            boolean pageActionBusinessTerminalObserved,
            boolean structuredPresentationObserved) {
    }
}
