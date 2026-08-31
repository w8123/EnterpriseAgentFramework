package com.enterprise.ai.control.pageworkbench.application;

import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ReadinessItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * Public page-workbench preflight for the exact model bound to a published
 * Agent configuration. Only non-sensitive catalog and test-state fields are
 * exposed as evidence.
 */
@Service
@RequiredArgsConstructor
public class PageWorkbenchAgentModelReadinessApplicationService {

    public static final String KEY = "AGENT_MODEL_RUNTIME_READY";
    private static final String LABEL = "Agent 模型可用性";
    private static final long MAX_TEST_AGE_HOURS = 24L;

    private final PageWorkbenchModelPort modelCatalogClient;
    private final ObjectMapper objectMapper;

    public ReadinessItem evaluate(String modelInstanceId) {
        if (!StringUtils.hasText(modelInstanceId)) {
            return item(
                    "PENDING",
                    "当前已发布的 Agent 配置未返回 modelInstanceId，无法在真实验收前确认模型可用性。",
                    evidence(null));
        }
        String targetId = modelInstanceId.trim();
        try {
            Map<String, Object> model = modelData(
                    modelCatalogClient.getInternal(targetId));
            if (model.isEmpty()) {
                return item(
                        "FAIL",
                        "Agent 绑定的模型实例不存在。请在模型中心修正 Agent 模型配置后重新发布。",
                        evidence(targetId));
            }

            ObjectNode evidence = evidence(targetId);
            putText(evidence, "name", model.get("name"));
            putText(evidence, "provider", model.get("provider"));
            putText(evidence, "modelName", model.get("modelName"));
            String status = text(model.get("status"));
            String lastTestStatus = text(model.get("lastTestStatus"));
            String lastTestAtText = text(model.get("lastTestAt"));
            putText(evidence, "status", status);
            putText(evidence, "lastTestStatus", lastTestStatus);
            putText(evidence, "lastTestAt", lastTestAtText);
            evidence.put("maxTestAgeHours", MAX_TEST_AGE_HOURS);

            if (!"ACTIVE".equalsIgnoreCase(status)) {
                return item(
                        "FAIL",
                        "Agent 绑定的模型当前未启用。请在模型中心启用并测试通过后再开始真实验收。",
                        evidence);
            }
            if ("FAILED".equalsIgnoreCase(lastTestStatus)) {
                return item(
                        "FAIL",
                        "Agent 绑定的模型最近一次测试失败。请在模型中心修复配置、额度或网络问题，并重新测试通过后再验收。",
                        evidence);
            }
            if (!"SUCCESS".equalsIgnoreCase(lastTestStatus)) {
                return item(
                        "PENDING",
                        "Agent 绑定的模型尚未测试通过。请先在模型中心执行测试，避免把模型故障留到业务页面才发现。",
                        evidence);
            }

            LocalDateTime lastTestAt = parseDateTime(lastTestAtText);
            if (lastTestAt == null) {
                return item(
                        "PENDING",
                        "模型显示测试通过，但缺少测试时间。请在模型中心重新测试后再开始真实验收。",
                        evidence);
            }
            long ageHours = Math.max(0L, Duration.between(
                    lastTestAt,
                    LocalDateTime.now()).toHours());
            evidence.put("testAgeHours", ageHours);
            if (ageHours > MAX_TEST_AGE_HOURS) {
                return item(
                        "PENDING",
                        "模型上次测试已超过 24 小时。请在模型中心重新测试，确认当前额度与网络仍然可用。",
                        evidence);
            }
            return item(
                    "PASS",
                    "Agent 绑定的模型已启用，并在最近 24 小时内测试通过。",
                    evidence);
        } catch (RuntimeException ex) {
            return item(
                    "WARN",
                    "模型中心暂时不可用，无法完成真实验收预检。请恢复模型服务后重新检查。",
                    evidence(targetId));
        }
    }

    public ReadinessItem pageOnly() {
        ObjectNode evidence = evidence(null);
        evidence.put("mode", "PAGE_ONLY");
        return item(
                "PASS",
                "本次为未绑定 Workflow 的页面级验收，不要求校验 Agent 模型。",
                evidence);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> modelData(
            ResponseEntity<Map<String, Object>> response) {
        Map<String, Object> body = response == null ? null : response.getBody();
        if (body == null || body.isEmpty()) {
            return Map.of();
        }
        Object data = body.get("data");
        if (data instanceof Map<?, ?> values) {
            return (Map<String, Object>) values;
        }
        return body.containsKey("id") ? body : Map.of();
    }

    private ReadinessItem item(
            String status,
            String message,
            ObjectNode evidence) {
        return new ReadinessItem(KEY, LABEL, status, message, evidence);
    }

    private ObjectNode evidence(String modelInstanceId) {
        ObjectNode evidence = objectMapper.createObjectNode();
        evidence.put("source", "reachai-model-service");
        evidence.put("observationType", "MODEL_INSTANCE_TEST_STATE");
        if (StringUtils.hasText(modelInstanceId)) {
            evidence.put("modelInstanceId", modelInstanceId.trim());
        }
        return evidence;
    }

    private static void putText(
            ObjectNode target,
            String field,
            Object value) {
        String text = text(value);
        if (StringUtils.hasText(text)) {
            target.put(field, text);
        }
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private static LocalDateTime parseDateTime(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return LocalDateTime.parse(value.trim());
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
