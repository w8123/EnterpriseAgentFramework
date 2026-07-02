package com.enterprise.ai.capability.catalog.composition;

import com.enterprise.ai.capability.client.runtime.CapabilityRuntimeInteractionClient;
import com.enterprise.ai.capability.internal.CapabilityToolExecutionService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CapabilityCompositionInteractionResumeService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final CapabilityRuntimeInteractionClient interactionClient;
    private final ObjectMapper objectMapper;
    private final CapabilityToolExecutionService executionService;

    public ResumeResult resumeAdminTest(String compositionName,
                                        String interactionId,
                                        String action,
                                        Map<String, Object> values) {
        if (!StringUtils.hasText(interactionId)) {
            return ResumeResult.failed("interactionId 不能为空", null);
        }
        CapabilityRuntimeInteractionClient.SkillInteractionRecord row = getInteraction(interactionId);
        if (row == null) {
            return ResumeResult.failed("交互不存在或已失效", null);
        }
        if (!compositionName.equals(row.skillName())) {
            return ResumeResult.failed("交互与当前 Composition 不匹配", null);
        }
        if (!CapabilityCompositionInteractionService.COMPOSITION_TEST_SESSION_ID.equals(row.userId())) {
            return ResumeResult.failed("该交互不属于管理端测试会话，无法在此继续", interactionId);
        }
        if (row.expiresAt() != null && row.expiresAt().isBefore(java.time.LocalDateTime.now())) {
            update(interactionId, CapabilitySkillInteractionStatus.EXPIRED, null, null, null, null);
            return ResumeResult.failed("交互已超时，请重新发起", interactionId);
        }
        if (!CapabilitySkillInteractionStatus.PENDING.equalsIgnoreCase(row.status())) {
            return ResumeResult.failed("该交互已结束，无法继续", interactionId);
        }

        String normalizedAction = action == null ? "" : action.trim().toLowerCase();
        if ("cancel".equals(normalizedAction)) {
            update(interactionId, CapabilitySkillInteractionStatus.CANCELLED, null, null, null, null);
            return new ResumeResult(true, "已取消操作", null, false, null, null);
        }
        try {
            JsonNode spec = objectMapper.readTree(row.specSnapshot());
            Map<String, Object> slots = slots(row.slotState());
            if (values != null) {
                values.forEach((key, value) -> {
                    if (value != null) {
                        slots.put(key, value);
                    }
                });
            }
            String phase = phase(row.slotState());
            if ("modify".equals(normalizedAction)) {
                List<String> keys = fieldKeys(spec);
                Map<String, Object> uiRequest = formUiRequest(row, spec, keys, slots);
                update(interactionId, null, writeSlotState("COLLECT", slots), writeJson(keys), writeJson(uiRequest), null);
                return new ResumeResult(true, "请修改表单内容。", null, true, interactionId, uiRequest);
            }
            if (!isSubmitAction(normalizedAction)) {
                return ResumeResult.failed("不支持的 action: " + normalizedAction, interactionId);
            }
            if ("COLLECT".equalsIgnoreCase(phase)) {
                List<String> missing = missingRequiredKeys(spec, slots);
                if (!missing.isEmpty()) {
                    Map<String, Object> uiRequest = formUiRequest(row, spec, missing, slots);
                    update(interactionId, null, writeSlotState("COLLECT", slots), writeJson(missing), writeJson(uiRequest), null);
                    return new ResumeResult(true, "请继续补充信息。", null, true, interactionId, uiRequest);
                }
                Map<String, Object> uiRequest = summaryUiRequest(row, spec, slots);
                update(interactionId, null, writeSlotState("CONFIRM", slots), null, writeJson(uiRequest), true);
                return new ResumeResult(true, "请确认信息后提交。", null, true, interactionId, uiRequest);
            }

            String targetTool = text(spec, "targetTool");
            if (!StringUtils.hasText(targetTool)) {
                return ResumeResult.failed("InteractiveForm composition targetTool is missing: " + compositionName, interactionId);
            }
            Map<String, Object> response = executionService.execute(targetTool, Map.of("input", slots));
            update(interactionId, CapabilitySkillInteractionStatus.SUBMITTED, null, null, null, null);
            return new ResumeResult(
                    true,
                    String.valueOf(response == null ? null : response.get("data")),
                    null,
                    false,
                    null,
                    null);
        } catch (Exception ex) {
            return ResumeResult.failed(ex.getMessage(), interactionId);
        }
    }

    private boolean isSubmitAction(String action) {
        return "submit".equals(action) || "confirm".equals(action);
    }

    private String phase(String slotStateJson) throws Exception {
        if (!StringUtils.hasText(slotStateJson)) {
            return null;
        }
        JsonNode slotState = objectMapper.readTree(slotStateJson);
        return text(slotState, "phase");
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null ? null : value.asText(null);
    }

    private Map<String, Object> slots(String slotStateJson) throws Exception {
        if (!StringUtils.hasText(slotStateJson)) {
            return new LinkedHashMap<>();
        }
        JsonNode slotState = objectMapper.readTree(slotStateJson);
        JsonNode slots = slotState == null ? null : slotState.get("slots");
        if (slots == null || !slots.isObject()) {
            return new LinkedHashMap<>();
        }
        return new LinkedHashMap<>(objectMapper.convertValue(slots, MAP_TYPE));
    }

    private List<String> missingRequiredKeys(JsonNode spec, Map<String, Object> slots) {
        List<String> missing = new ArrayList<>();
        JsonNode fields = spec == null ? null : spec.get("fields");
        if (fields == null || !fields.isArray()) {
            return missing;
        }
        for (JsonNode field : fields) {
            if (!field.path("required").asBoolean(false)) {
                continue;
            }
            String key = text(field, "key");
            Object value = key == null ? null : slots.get(key);
            if (!StringUtils.hasText(key) || value == null || String.valueOf(value).isBlank()) {
                if (StringUtils.hasText(key)) {
                    missing.add(key);
                }
            }
        }
        return missing;
    }

    private List<String> fieldKeys(JsonNode spec) {
        List<String> keys = new ArrayList<>();
        JsonNode fields = spec == null ? null : spec.get("fields");
        if (fields == null || !fields.isArray()) {
            return keys;
        }
        for (JsonNode field : fields) {
            String key = text(field, "key");
            if (StringUtils.hasText(key)) {
                keys.add(key);
            }
        }
        return keys;
    }

    private Map<String, Object> formUiRequest(CapabilityRuntimeInteractionClient.SkillInteractionRecord row,
                                              JsonNode spec,
                                              List<String> missing,
                                              Map<String, Object> slots) {
        List<Map<String, Object>> fields = new ArrayList<>();
        JsonNode specFields = spec == null ? null : spec.get("fields");
        if (specFields != null && specFields.isArray()) {
            for (JsonNode field : specFields) {
                String key = text(field, "key");
                if (key != null && missing.contains(key)) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("key", key);
                    item.put("label", text(field, "label"));
                    item.put("type", text(field, "type"));
                    item.put("required", field.path("required").asBoolean(false));
                    fields.add(item);
                }
            }
        }
        Map<String, Object> uiRequest = new LinkedHashMap<>();
        uiRequest.put("component", "form");
        uiRequest.put("interactionId", row.id());
        uiRequest.put("traceId", row.traceId());
        uiRequest.put("skillName", row.skillName());
        uiRequest.put("title", "请补充信息");
        uiRequest.put("fields", fields);
        uiRequest.put("prefilled", slots);
        uiRequest.put("missing", missing);
        return uiRequest;
    }

    private Map<String, Object> summaryUiRequest(CapabilityRuntimeInteractionClient.SkillInteractionRecord row,
                                                 JsonNode spec,
                                                 Map<String, Object> slots) {
        Map<String, Object> summary = new LinkedHashMap<>();
        JsonNode fields = spec == null ? null : spec.get("fields");
        if (fields != null && fields.isArray()) {
            for (JsonNode field : fields) {
                String key = text(field, "key");
                String label = text(field, "label");
                if (StringUtils.hasText(key)) {
                    summary.put(StringUtils.hasText(label) ? label : key, slots.get(key));
                }
            }
        }
        Map<String, Object> uiRequest = new LinkedHashMap<>();
        uiRequest.put("component", "summary_card");
        uiRequest.put("interactionId", row.id());
        uiRequest.put("traceId", row.traceId());
        uiRequest.put("skillName", row.skillName());
        uiRequest.put("title", StringUtils.hasText(text(spec, "confirmTitle")) ? text(spec, "confirmTitle") : "请确认提交");
        uiRequest.put("summary", summary);
        uiRequest.put("message", "确认无误后点击提交；如需修改请点击修改。");
        uiRequest.put("actions", List.of(
                Map.of("action", "modify", "label", "修改"),
                Map.of("action", "submit", "label", "提交")
        ));
        return uiRequest;
    }

    private String writeSlotState(String phase, Map<String, Object> slots) throws Exception {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("phase", phase);
        state.put("slots", slots);
        return writeJson(state);
    }

    private String writeJson(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private void update(String interactionId,
                        String status,
                        String slotState,
                        String pendingKeys,
                        String uiPayload,
                        Boolean clearPendingKeys) {
        interactionClient.updateInteraction(interactionId,
                new CapabilityRuntimeInteractionClient.InteractionUpdateRequest(
                        status,
                        slotState,
                        pendingKeys,
                        uiPayload,
                        clearPendingKeys));
    }

    private CapabilityRuntimeInteractionClient.SkillInteractionRecord getInteraction(String interactionId) {
        try {
            return interactionClient.getInteraction(interactionId);
        } catch (FeignException.NotFound ignored) {
            return null;
        }
    }

    public record ResumeResult(boolean success,
                               String result,
                               String errorMessage,
                               boolean interactionPending,
                               String interactionId,
                               Map<String, Object> uiRequest) {

        static ResumeResult failed(String errorMessage, String interactionId) {
            return new ResumeResult(false, "", errorMessage, false, interactionId, null);
        }
    }
}
