package com.enterprise.ai.control.pageworkbench.application;

import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ReadinessItem;
import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.PublishedWorkflowView;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.WorkflowExecutionReadinessView;
import com.enterprise.ai.control.platform.PlatformEmbedE2eEvidenceService;
import com.enterprise.ai.control.platform.PlatformEmbedE2eEvidenceService.EmbedTraceCandidate;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.BAD_GATEWAY;

/**
 * Joins an exact Control-plane page conversation to Runtime execution facts.
 * AI Coding reports can select a trace, but cannot make the gate pass without
 * the corresponding server-observed session, Workflow/version and spans.
 */
@Service
@RequiredArgsConstructor
public class PageWorkbenchWorkflowTraceReadinessApplicationService {

    public static final String KEY = "PAGE_WORKFLOW_TRACE_READY";
    private static final String LABEL = "页面 Workflow 执行链路";

    private final PlatformEmbedE2eEvidenceService embedEvidence;
    private final RuntimeProxyClient runtimeClient;
    private final ObjectMapper objectMapper;

    public WorkflowAcceptanceTarget requirePublishedTarget(
            String projectCode,
            String pageKey,
            WorkflowAcceptanceTarget target) {
        if (target == null) {
            return null;
        }
        if (!target.complete()) {
            throw new IllegalArgumentException(
                    "related WORKFLOW target requires workflowVersionId and workflowVersion");
        }
        String expectedProjectCode = requireText(
                projectCode,
                "projectCode");
        String expectedPageKey = requireText(pageKey, "pageKey");
        try {
            List<PublishedWorkflowView> published =
                    runtimeClient.pageWorkbenchPublished(
                            expectedProjectCode,
                            expectedPageKey)
                    .getBody();
            PublishedWorkflowView exact = defaultList(published).stream()
                    .filter(item -> expectedPageKey.equals(item.pageKey()))
                    .filter(item -> target.workflowId().equals(
                            item.workflowId()))
                    .filter(item -> target.workflowVersionId().equals(
                            item.workflowVersionId()))
                    .filter(item -> target.workflowVersion().equals(
                            item.workflowVersion()))
                    .filter(item -> "ACTIVE".equalsIgnoreCase(
                            item.workflowStatus()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "related WORKFLOW target is not an exact published workflow for the current page"));
            return new WorkflowAcceptanceTarget(
                    exact.workflowId(),
                    exact.workflowVersionId(),
                    exact.workflowVersion(),
                    exact.workflowName());
        } catch (FeignException ex) {
            throw new ResponseStatusException(
                    BAD_GATEWAY,
                    "Runtime published workflow validation is unavailable: "
                            + ex.status(),
                    ex);
        }
    }

    public ReadinessItem evaluate(
            String projectCode,
            String pageKey,
            LocalDateTime observedAfter,
            WorkflowAcceptanceTarget target,
            String reportedTraceId) {
        if (target == null) {
            ObjectNode evidence = baseEvidence(
                    projectCode,
                    pageKey,
                    null,
                    reportedTraceId);
            evidence.put("mode", "PAGE_ONLY");
            return new ReadinessItem(
                    KEY,
                    LABEL,
                    "PASS",
                    "本次为页面级验收，未绑定指定 Workflow 版本",
                    evidence);
        }
        if (!target.complete()) {
            return pending(
                    "验收任务绑定的 Workflow 目标不完整，请从“已发布”列表重新发起验收",
                    baseEvidence(
                            projectCode,
                            pageKey,
                            target,
                            reportedTraceId));
        }

        List<EmbedTraceCandidate> candidates =
                distinctCandidates(embedEvidence.successfulTraceCandidates(
                        projectCode,
                        pageKey,
                        observedAfter));
        String expectedTraceId = normalizeOptional(reportedTraceId);
        if (expectedTraceId != null) {
            candidates = candidates.stream()
                    .filter(candidate -> expectedTraceId.equals(
                            candidate.traceId()))
                    .toList();
            if (candidates.isEmpty()) {
                return pending(
                        "AI Coding 回传的 traceId 未出现在本次任务启动后的当前页面会话中",
                        baseEvidence(
                                projectCode,
                                pageKey,
                                target,
                                expectedTraceId));
            }
        }
        if (candidates.isEmpty()) {
            return pending(
                    "本次任务启动后，尚未观察到当前页面助手回复关联的 Runtime Trace",
                    baseEvidence(
                            projectCode,
                            pageKey,
                            target,
                            expectedTraceId));
        }

        WorkflowExecutionReadinessView latestObserved = null;
        List<WorkflowExecutionReadinessView> eligible = new ArrayList<>();
        for (EmbedTraceCandidate candidate : candidates) {
            if (!StringUtils.hasText(candidate.pageInstanceId())) {
                continue;
            }
            try {
                WorkflowExecutionReadinessView observed =
                        runtimeClient.pageWorkbenchExecutionReadiness(
                                projectCode,
                                pageKey,
                                candidate.sessionId(),
                                candidate.pageInstanceId(),
                                candidate.traceId(),
                                target.workflowId(),
                                target.workflowVersionId(),
                                target.workflowVersion())
                        .getBody();
                if (observed == null) {
                    continue;
                }
                latestObserved = observed;
                if ("PASS".equalsIgnoreCase(observed.status())) {
                    eligible.add(observed);
                }
            } catch (FeignException ex) {
                ObjectNode evidence = baseEvidence(
                        projectCode,
                        pageKey,
                        target,
                        expectedTraceId);
                evidence.put("source", "reachai-runtime-service");
                evidence.put("httpStatus", ex.status());
                return pending(
                        "Runtime 暂时不可用，尚未完成页面 Workflow 执行链路校验",
                        evidence);
            }
        }

        if (eligible.size() == 1) {
            WorkflowExecutionReadinessView observed = eligible.get(0);
            return new ReadinessItem(
                    KEY,
                    LABEL,
                    "PASS",
                    firstText(
                            observed.message(),
                            "ReachAI 已确认页面 Workflow 执行链路"),
                    runtimeEvidence(observed));
        }
        if (eligible.size() > 1) {
            ObjectNode evidence = baseEvidence(
                    projectCode,
                    pageKey,
                    target,
                    expectedTraceId);
            evidence.put("eligibleTraceCount", eligible.size());
            var traceIds = evidence.putArray("eligibleTraceIds");
            eligible.stream()
                    .map(WorkflowExecutionReadinessView::traceId)
                    .filter(StringUtils::hasText)
                    .distinct()
                    .forEach(traceIds::add);
            return pending(
                    "观察到多条满足条件的 Workflow Trace，请让 AI Coding 使用新的 artifactKey 回传明确的 traceId",
                    evidence);
        }

        if (latestObserved != null) {
            return pending(
                    firstText(
                            latestObserved.message(),
                            "当前页面 Trace 尚未满足指定 Workflow 版本的执行条件"),
                    runtimeEvidence(latestObserved));
        }
        return pending(
                "当前页面会话缺少可关联的 pageInstanceId，无法校验 Runtime 执行链路",
                baseEvidence(
                        projectCode,
                        pageKey,
                        target,
                        expectedTraceId));
    }

    private ObjectNode baseEvidence(
            String projectCode,
            String pageKey,
            WorkflowAcceptanceTarget target,
            String reportedTraceId) {
        ObjectNode evidence = objectMapper.createObjectNode();
        evidence.put("source", "reachai-control-service");
        evidence.put("observationType", "EMBED_WORKFLOW_TRACE");
        evidence.put("projectCode", projectCode);
        evidence.put("pageKey", pageKey);
        if (target != null) {
            evidence.put("workflowId", target.workflowId());
            if (target.workflowVersionId() != null) {
                evidence.put(
                        "workflowVersionId",
                        target.workflowVersionId());
            }
            evidence.put("workflowVersion", target.workflowVersion());
        }
        if (StringUtils.hasText(reportedTraceId)) {
            evidence.put("reportedTraceId", reportedTraceId.trim());
        }
        return evidence;
    }

    private ObjectNode runtimeEvidence(
            WorkflowExecutionReadinessView observed) {
        ObjectNode evidence = objectMapper.createObjectNode();
        evidence.put("source", "reachai-runtime-service");
        putText(evidence, "status", observed.status());
        putText(evidence, "code", observed.code());
        putText(evidence, "projectCode", observed.projectCode());
        putText(evidence, "pageKey", observed.pageKey());
        putText(evidence, "sessionId", observed.sessionId());
        putText(evidence, "pageInstanceId", observed.pageInstanceId());
        putText(evidence, "traceId", observed.traceId());
        putText(evidence, "workflowId", observed.workflowId());
        if (observed.workflowVersionId() != null) {
            evidence.put(
                    "workflowVersionId",
                    observed.workflowVersionId());
        }
        putText(
                evidence,
                "workflowVersion",
                observed.workflowVersion());
        putText(evidence, "runStatus", observed.runStatus());
        putText(evidence, "entryType", observed.entryType());
        evidence.put("workflowObserved", observed.workflowObserved());
        if (observed.checkedAt() != null) {
            evidence.put("checkedAt", observed.checkedAt().toString());
        }
        return evidence;
    }

    private static void putText(
            ObjectNode target,
            String field,
            String value) {
        if (StringUtils.hasText(value)) {
            target.put(field, value);
        }
    }

    private ReadinessItem pending(
            String message,
            com.fasterxml.jackson.databind.JsonNode evidence) {
        return new ReadinessItem(
                KEY,
                LABEL,
                "PENDING",
                message,
                evidence);
    }

    private static String normalizeOptional(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private static List<EmbedTraceCandidate> distinctCandidates(
            List<EmbedTraceCandidate> candidates) {
        Map<String, EmbedTraceCandidate> byTraceId = new LinkedHashMap<>();
        for (EmbedTraceCandidate candidate : defaultList(candidates)) {
            if (candidate != null && StringUtils.hasText(candidate.traceId())) {
                byTraceId.putIfAbsent(candidate.traceId(), candidate);
            }
        }
        return List.copyOf(byTraceId.values());
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

    private static String firstText(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    public record WorkflowAcceptanceTarget(
            String workflowId,
            Long workflowVersionId,
            String workflowVersion,
            String workflowName) {

        public boolean complete() {
            return StringUtils.hasText(workflowId)
                    && workflowVersionId != null
                    && workflowVersionId > 0
                    && StringUtils.hasText(workflowVersion);
        }
    }
}
