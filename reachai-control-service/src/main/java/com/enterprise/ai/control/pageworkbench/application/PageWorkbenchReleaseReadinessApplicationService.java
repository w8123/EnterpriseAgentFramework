package com.enterprise.ai.control.pageworkbench.application;

import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ReadinessItem;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.WorkflowReleaseReadinessView;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class PageWorkbenchReleaseReadinessApplicationService {

    public static final String KEY = "PRE_RELEASE_READY";
    private static final String LABEL = "Workflow 发布前检查";

    private final PageWorkbenchRuntimePort runtimeClient;
    private final ObjectMapper objectMapper;

    public ReadinessItem evaluate(
            String projectCode,
            String pageKey,
            String workflowId,
            String workflowVersion) {
        try {
            WorkflowReleaseReadinessView body =
                    runtimeClient.pageWorkbenchReleaseReadiness(
                            requireText(projectCode, "projectCode"),
                            requireText(pageKey, "pageKey"),
                            requireText(workflowId, "workflowId"),
                            requireText(workflowVersion, "workflowVersion"))
                    .getBody();
            if (body == null) {
                return pending(
                        "Runtime 未返回 Workflow 发布前检查结果",
                        null);
            }
            String status = normalizeStatus(body.status());
            return new ReadinessItem(
                    KEY,
                    LABEL,
                    status,
                    firstText(
                            body.message(),
                            "ReachAI 已完成 Workflow 发布前检查"),
                    objectMapper.valueToTree(body));
        } catch (FeignException ex) {
            ObjectNode evidence = objectMapper.createObjectNode();
            evidence.put("source", "reachai-runtime-service");
            evidence.put("httpStatus", ex.status());
            return pending(
                    "Runtime 暂时不可用，尚未完成 Workflow 发布前检查",
                    evidence);
        }
    }

    public ReadinessItem waitingForArtifact() {
        return pending(
                "等待 AI Coding 回传目标 workflowId 和 workflowVersion",
                null);
    }

    private ReadinessItem pending(
            String message,
            ObjectNode evidence) {
        return new ReadinessItem(
                KEY,
                LABEL,
                "PENDING",
                message,
                evidence);
    }

    private static String normalizeStatus(String value) {
        if (!StringUtils.hasText(value)) {
            return "PENDING";
        }
        String normalized = value.trim().toUpperCase(java.util.Locale.ROOT);
        return "PASS".equals(normalized) || "FAIL".equals(normalized)
                ? normalized
                : "PENDING";
    }

    private static String requireText(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private static String firstText(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }
}
