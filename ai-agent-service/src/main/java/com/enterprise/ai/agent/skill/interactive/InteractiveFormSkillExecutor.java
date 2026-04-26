package com.enterprise.ai.agent.skill.interactive;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.ai.agent.model.AgentResult;
import com.enterprise.ai.agent.model.interactive.UiFieldOptionPayload;
import com.enterprise.ai.agent.model.interactive.UiFieldPayload;
import com.enterprise.ai.agent.model.interactive.UiRequestPayload;
import com.enterprise.ai.agent.model.interactive.UiSubmitPayload;
import com.enterprise.ai.agent.skill.ToolExecutionContextHolder;
import com.enterprise.ai.agent.tool.log.ToolExecutionContext;
import com.enterprise.ai.agent.tools.ToolRegistry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * InteractiveFormSkill 状态机：预拉选项、槽抽取、挂起表单/确认卡、恢复合并、提交 targetTool。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InteractiveFormSkillExecutor {

    public static final int MAX_PENDING_PER_USER = 5;
    public static final int DEFAULT_TTL_SECONDS = 1800;

    private final ToolRegistry toolRegistry;
    private final InteractiveDictLookup dictLookup;
    private final SlotExtractionService slotExtractionService;
    private final SkillInteractionMapper skillInteractionMapper;
    private final ObjectMapper objectMapper;

    public Object start(InteractiveFormSkill skill, Map<String, Object> args, ToolExecutionContext ctx) {
        if (ctx == null || ctx.getTraceId() == null || ctx.getTraceId().isBlank()) {
            throw new IllegalStateException("InteractiveFormSkill 需要有效的 ToolExecutionContext.traceId");
        }
        String userId = ctx.getUserId();
        if (userId != null && !userId.isBlank()
                && countPendingForUser(userId) >= MAX_PENDING_PER_USER) {
            throw new IllegalStateException("当前用户未完成的交互过多，请先完成或取消后再试（上限 "
                    + MAX_PENDING_PER_USER + "）");
        }

        InteractiveFormSpec spec = skill.getSpec();
        Map<String, List<FieldOptionSpec>> optionsByField = prefetchAll(spec);

        Map<String, Object> slots = new LinkedHashMap<>();
        for (InteractiveFormFieldTree.LeafBinding lb : InteractiveFormFieldTree.flattenLeaves(spec.getFields(), List.of())) {
            FieldSpec f = lb.field();
            if (f.getDefaultValue() != null) {
                slots.put(f.getKey(), f.getDefaultValue());
            }
        }

        String userText = resolveUserText(args, ctx);
        slotExtractionService.mergeFromUserText(slots, userText, spec, optionsByField);

        List<String> missing = computeMissing(spec, slots);
        String interactionId = UUID.randomUUID().toString().replace("-", "");
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime exp = now.plusSeconds(DEFAULT_TTL_SECONDS);

        if (!missing.isEmpty()) {
            SlotStateDocument state = SlotStateDocument.builder()
                    .phase(SlotStateDocument.PHASE_COLLECT)
                    .slots(slots)
                    .build();
            List<String> batch = pickBatch(missing, Math.max(1, spec.getBatchSize()));
            persistNew(interactionId, ctx, skill.name(), state, batch,
                    writePayloadJson(buildFormPayload(interactionId, ctx, skill.name(), spec, batch, slots, optionsByField)),
                    spec, now, exp);
            throw new InteractionSuspendedException(
                    buildFormPayload(interactionId, ctx, skill.name(), spec, batch, slots, optionsByField),
                    "需要您补充或确认部分信息，请在前端表单中继续操作。");
        }

        SlotStateDocument state = SlotStateDocument.builder()
                .phase(SlotStateDocument.PHASE_CONFIRM)
                .slots(slots)
                .build();
        UiRequestPayload summary = buildSummaryPayload(interactionId, ctx, skill.name(), spec, slots);
        persistNew(interactionId, ctx, skill.name(), state, List.of(),
                writePayloadJson(summary), spec, now, exp);
        throw new InteractionSuspendedException(summary,
                "信息已齐全，请确认是否提交。");
    }

    public AgentResult resume(String interactionId,
                              UiSubmitPayload submit,
                              String userId,
                              List<String> roles,
                              String sessionId) {
        if (interactionId == null || interactionId.isBlank()) {
            return AgentResult.builder().success(false).answer("interactionId 不能为空").build();
        }
        SkillInteractionEntity row = skillInteractionMapper.selectById(interactionId);
        if (row == null) {
            return AgentResult.builder().success(false).answer("交互会话不存在或已失效").build();
        }
        if (row.getExpiresAt() != null && row.getExpiresAt().isBefore(LocalDateTime.now())) {
            row.setStatus(SkillInteractionStatus.EXPIRED);
            row.setUpdatedAt(LocalDateTime.now());
            skillInteractionMapper.updateById(row);
            return AgentResult.builder().success(false).answer("交互已超时，请重新发起").build();
        }
        if (!SkillInteractionStatus.PENDING.equalsIgnoreCase(row.getStatus())) {
            return AgentResult.builder().success(false).answer("该交互已结束，无法继续").build();
        }
        if (row.getUserId() != null && !row.getUserId().isBlank()
                && userId != null && !userId.isBlank()
                && !row.getUserId().equals(userId)) {
            return AgentResult.builder().success(false).answer("无权继续此交互").build();
        }

        InteractiveFormSpec spec;
        try {
            spec = objectMapper.readValue(row.getSpecSnapshot(), InteractiveFormSpec.class);
        } catch (Exception e) {
            return AgentResult.builder().success(false).answer("spec 快照损坏").build();
        }
        SlotStateDocument state = readSlotState(row.getSlotState());
        Map<String, Object> slots = new LinkedHashMap<>(state.getSlots() == null ? Map.of() : state.getSlots());
        Map<String, List<FieldOptionSpec>> optionsByField = prefetchAll(spec);

        String action = submit == null || submit.getAction() == null ? "" : submit.getAction().trim().toLowerCase(Locale.ROOT);
        if ("cancel".equals(action)) {
            row.setStatus(SkillInteractionStatus.CANCELLED);
            row.setUpdatedAt(LocalDateTime.now());
            skillInteractionMapper.updateById(row);
            return AgentResult.builder().success(true).answer("已取消操作").build();
        }

        ToolExecutionContext ctx = ToolExecutionContext.builder()
                .traceId(row.getTraceId())
                .sessionId(sessionId != null ? sessionId : row.getSessionId())
                .userId(userId != null ? userId : row.getUserId())
                .agentName("skill:" + row.getSkillName())
                .roles(roles)
                .allowIrreversible(false)
                .build();

        if (SlotStateDocument.PHASE_COLLECT.equalsIgnoreCase(state.getPhase())) {
            Map<String, Object> incoming = submit.getValues() == null ? Map.of() : submit.getValues();
            for (Map.Entry<String, Object> e : incoming.entrySet()) {
                if (e.getValue() != null) {
                    slots.put(e.getKey(), e.getValue());
                }
            }
            for (InteractiveFormFieldTree.LeafBinding lb : InteractiveFormFieldTree.flattenLeaves(spec.getFields(), List.of())) {
                FieldSpec f = lb.field();
                Object v = slots.get(f.getKey());
                String err = slotExtractionService.validateField(f, v);
                if (err != null && incoming.containsKey(f.getKey())) {
                    return AgentResult.builder().success(false).answer("字段「" + f.getLabel() + "」校验失败：" + err).build();
                }
            }
            List<String> missing = computeMissing(spec, slots);
            if (!missing.isEmpty()) {
                List<String> batch = pickBatch(missing, Math.max(1, spec.getBatchSize()));
                state.setSlots(slots);
                state.setPhase(SlotStateDocument.PHASE_COLLECT);
                row.setSlotState(writeSlotState(state));
                row.setPendingKeys(writeJson(batch));
                UiRequestPayload form = buildFormPayload(interactionId, ctx, row.getSkillName(), spec, batch, slots, optionsByField);
                row.setUiPayload(writePayloadJson(form));
                row.setUpdatedAt(LocalDateTime.now());
                skillInteractionMapper.updateById(row);
                return AgentResult.builder()
                        .success(true)
                        .answer("请继续补充信息。")
                        .uiRequest(form)
                        .metadata(baseMeta(row))
                        .build();
            }
            state.setSlots(slots);
            state.setPhase(SlotStateDocument.PHASE_CONFIRM);
            row.setSlotState(writeSlotState(state));
            row.setPendingKeys(null);
            UiRequestPayload summary = buildSummaryPayload(interactionId, ctx, row.getSkillName(), spec, slots);
            row.setUiPayload(writePayloadJson(summary));
            row.setUpdatedAt(LocalDateTime.now());
            skillInteractionMapper.updateById(row);
            return AgentResult.builder()
                    .success(true)
                    .answer("请确认信息后提交。")
                    .uiRequest(summary)
                    .metadata(baseMeta(row))
                    .build();
        }

        if (SlotStateDocument.PHASE_CONFIRM.equalsIgnoreCase(state.getPhase())) {
            if ("modify".equals(action)) {
                state.setPhase(SlotStateDocument.PHASE_COLLECT);
                state.setSlots(slots);
                List<String> allKeys = InteractiveFormFieldTree.flattenLeaves(spec.getFields(), List.of()).stream()
                        .map(lb -> lb.field().getKey())
                        .toList();
                List<String> batch = pickBatch(allKeys, Math.max(1, spec.getBatchSize()));
                row.setSlotState(writeSlotState(state));
                row.setPendingKeys(writeJson(batch));
                UiRequestPayload form = buildFormPayload(interactionId, ctx, row.getSkillName(), spec, batch, slots, optionsByField);
                row.setUiPayload(writePayloadJson(form));
                row.setUpdatedAt(LocalDateTime.now());
                skillInteractionMapper.updateById(row);
                return AgentResult.builder()
                        .success(true)
                        .answer("请修改表单内容。")
                        .uiRequest(form)
                        .metadata(baseMeta(row))
                        .build();
            }
            if (!"submit".equals(action)) {
                return AgentResult.builder().success(false).answer("不支持的 action: " + action).build();
            }
            ToolExecutionContext prev = ToolExecutionContextHolder.get();
            ToolExecutionContextHolder.set(ctx);
            try {
                List<InteractiveFormFieldTree.LeafBinding> leaves =
                        InteractiveFormFieldTree.flattenLeaves(spec.getFields(), List.of());
                Map<String, Object> nested = InteractiveFormFieldTree.nestArgs(slots, leaves);
                Object execResult = toolRegistry.execute(spec.getTargetTool(), new LinkedHashMap<>(nested));
                row.setStatus(SkillInteractionStatus.SUBMITTED);
                row.setUpdatedAt(LocalDateTime.now());
                skillInteractionMapper.updateById(row);
                String msg = renderSuccess(spec, slots, execResult);
                return AgentResult.builder()
                        .success(true)
                        .answer(msg)
                        .metadata(baseMeta(row))
                        .build();
            } catch (Exception ex) {
                log.warn("[InteractiveForm] 提交 targetTool 失败: {}", ex.toString());
                return AgentResult.builder()
                        .success(false)
                        .answer("提交失败：" + ex.getMessage())
                        .metadata(baseMeta(row))
                        .build();
            } finally {
                ToolExecutionContextHolder.set(prev);
            }
        }

        return AgentResult.builder().success(false).answer("未知状态: " + state.getPhase()).build();
    }

    private Map<String, Object> baseMeta(SkillInteractionEntity row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("traceId", row.getTraceId());
        m.put("interactionId", row.getId());
        return m;
    }

    private long countPendingForUser(String userId) {
        return skillInteractionMapper.selectCount(new LambdaQueryWrapper<SkillInteractionEntity>()
                .eq(SkillInteractionEntity::getUserId, userId)
                .eq(SkillInteractionEntity::getStatus, SkillInteractionStatus.PENDING));
    }

    private String resolveUserText(Map<String, Object> args, ToolExecutionContext ctx) {
        String fromCtx = ctx.getCurrentTurnMessage();
        if (fromCtx != null && !fromCtx.isBlank()) {
            return fromCtx.trim();
        }
        Object um = args.get("userMessage");
        if (um == null) {
            um = args.get("message");
        }
        if (um == null) {
            um = args.get("task");
        }
        return um == null ? "" : String.valueOf(um).trim();
    }

    private Map<String, List<FieldOptionSpec>> prefetchAll(InteractiveFormSpec spec) {
        Map<String, List<FieldOptionSpec>> map = new LinkedHashMap<>();
        for (InteractiveFormFieldTree.LeafBinding lb : InteractiveFormFieldTree.flattenLeaves(spec.getFields(), List.of())) {
            FieldSpec f = lb.field();
            FieldSourceSpec src = f.getSource();
            if (src == null || src.getKind() == null) {
                map.put(f.getKey(), List.of());
                continue;
            }
            String kind = src.getKind().trim().toUpperCase(Locale.ROOT);
            switch (kind) {
                case "STATIC" -> map.put(f.getKey(), src.getOptions() == null ? List.of() : src.getOptions());
                case "DICT" -> map.put(f.getKey(), dictLookup.options(src.getDictCode()));
                case "TOOL_CALL" -> map.put(f.getKey(), loadToolOptions(src));
                default -> map.put(f.getKey(), List.of());
            }
        }
        return map;
    }

    private List<FieldOptionSpec> loadToolOptions(FieldSourceSpec src) {
        String tool = src.getToolName();
        if (tool == null || tool.isBlank()) {
            return List.of();
        }
        try {
            Object res = toolRegistry.execute(tool, src.getToolArgs() == null ? Map.of() : src.getToolArgs());
            return rowsToOptions(normalizeRows(res), src.getValueField(), src.getLabelField());
        } catch (Exception ex) {
            log.warn("[InteractiveForm] 预拉 Tool 失败 tool={} err={}", tool, ex.toString());
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> normalizeRows(Object res) {
        if (res == null) {
            return List.of();
        }
        if (res instanceof List<?> list) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    out.add((Map<String, Object>) m);
                }
            }
            return out;
        }
        if (res instanceof String s) {
            try {
                JsonNode n = objectMapper.readTree(s);
                if (n.isArray()) {
                    List<Map<String, Object>> out = new ArrayList<>();
                    for (JsonNode item : n) {
                        if (item.isObject()) {
                            out.add(objectMapper.convertValue(item, new TypeReference<Map<String, Object>>() {
                            }));
                        }
                    }
                    return out;
                }
            } catch (Exception ignored) {
                return List.of();
            }
        }
        return List.of();
    }

    private List<FieldOptionSpec> rowsToOptions(List<Map<String, Object>> rows, String valueField, String labelField) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        String vf = valueField == null || valueField.isBlank() ? "id" : valueField;
        String lf = labelField == null || labelField.isBlank() ? "name" : labelField;
        List<FieldOptionSpec> opts = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Object v = row.get(vf);
            Object l = row.get(lf);
            if (v == null) {
                continue;
            }
            opts.add(FieldOptionSpec.builder()
                    .value(String.valueOf(v))
                    .label(l == null ? String.valueOf(v) : String.valueOf(l))
                    .build());
        }
        return opts;
    }

    private List<String> computeMissing(InteractiveFormSpec spec, Map<String, Object> slots) {
        List<String> missing = new ArrayList<>();
        for (InteractiveFormFieldTree.LeafBinding lb : InteractiveFormFieldTree.flattenLeaves(spec.getFields(), List.of())) {
            FieldSpec f = lb.field();
            if (!f.isRequired()) {
                continue;
            }
            Object v = slots.get(f.getKey());
            if (v == null || String.valueOf(v).isBlank()) {
                missing.add(f.getKey());
            }
        }
        return missing;
    }

    private List<String> pickBatch(List<String> missing, int batchSize) {
        if (missing.size() <= batchSize) {
            return new ArrayList<>(missing);
        }
        return new ArrayList<>(missing.subList(0, batchSize));
    }

    private UiRequestPayload buildFormPayload(String interactionId,
                                              ToolExecutionContext ctx,
                                              String skillName,
                                              InteractiveFormSpec spec,
                                              List<String> batchKeys,
                                              Map<String, Object> slots,
                                              Map<String, List<FieldOptionSpec>> optionsByField) {
        List<UiFieldPayload> fields = new ArrayList<>();
        for (String key : batchKeys) {
            FieldSpec fs = InteractiveFormFieldTree.findLeafByKey(spec.getFields(), key);
            if (fs == null) {
                continue;
            }
            List<UiFieldOptionPayload> opts = optionsByField.getOrDefault(key, List.of()).stream()
                    .map(o -> UiFieldOptionPayload.builder().value(o.getValue()).label(o.getLabel()).build())
                    .collect(Collectors.toList());
            fields.add(UiFieldPayload.builder()
                    .key(fs.getKey())
                    .label(fs.getLabel())
                    .type(fs.getType())
                    .required(fs.isRequired())
                    .options(opts)
                    .build());
        }
        Map<String, Object> prefilled = new LinkedHashMap<>();
        for (String key : batchKeys) {
            if (slots.containsKey(key) && slots.get(key) != null) {
                prefilled.put(key, slots.get(key));
            }
        }
        return UiRequestPayload.builder()
                .component("form")
                .interactionId(interactionId)
                .traceId(ctx.getTraceId())
                .skillName(skillName)
                .title("请补充信息")
                .ttlSeconds(DEFAULT_TTL_SECONDS)
                .fields(fields)
                .prefilled(prefilled)
                .missing(batchKeys)
                .build();
    }

    private UiRequestPayload buildSummaryPayload(String interactionId,
                                                 ToolExecutionContext ctx,
                                                 String skillName,
                                                 InteractiveFormSpec spec,
                                                 Map<String, Object> slots) {
        Map<String, Object> summary = new LinkedHashMap<>();
        for (InteractiveFormFieldTree.LeafBinding lb : InteractiveFormFieldTree.flattenLeaves(spec.getFields(), List.of())) {
            FieldSpec f = lb.field();
            Object v = slots.get(f.getKey());
            summary.put(f.getLabel(), v == null ? "" : String.valueOf(v));
        }
        String title = spec.getConfirmTitle() != null && !spec.getConfirmTitle().isBlank()
                ? spec.getConfirmTitle()
                : "请确认提交";
        return UiRequestPayload.builder()
                .component("summary_card")
                .interactionId(interactionId)
                .traceId(ctx.getTraceId())
                .skillName(skillName)
                .title(title)
                .ttlSeconds(DEFAULT_TTL_SECONDS)
                .summary(summary)
                .message("确认无误后点击提交；如需修改请点击修改。")
                .build();
    }

    private void persistNew(String interactionId,
                            ToolExecutionContext ctx,
                            String skillName,
                            SlotStateDocument state,
                            List<String> pendingKeys,
                            String uiPayloadJson,
                            InteractiveFormSpec spec,
                            LocalDateTime createdAt,
                            LocalDateTime expiresAt) {
        SkillInteractionEntity e = new SkillInteractionEntity();
        e.setId(interactionId);
        e.setTraceId(ctx.getTraceId());
        e.setSessionId(ctx.getSessionId());
        e.setUserId(ctx.getUserId());
        e.setAgentId(null);
        e.setSkillName(skillName);
        e.setStatus(SkillInteractionStatus.PENDING);
        e.setSlotState(writeSlotState(state));
        e.setPendingKeys(writeJson(pendingKeys));
        e.setUiPayload(uiPayloadJson);
        try {
            e.setSpecSnapshot(objectMapper.writeValueAsString(spec));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
        e.setCreatedAt(createdAt);
        e.setUpdatedAt(createdAt);
        e.setExpiresAt(expiresAt);
        skillInteractionMapper.insert(e);
    }

    private String renderSuccess(InteractiveFormSpec spec, Map<String, Object> slots, Object execResult) {
        String tpl = spec.getSuccessTemplate();
        if (tpl == null || tpl.isBlank()) {
            return "操作成功：" + execResult;
        }
        String out = tpl;
        for (Map.Entry<String, Object> e : slots.entrySet()) {
            out = out.replace("{{" + e.getKey() + "}}", e.getValue() == null ? "" : String.valueOf(e.getValue()));
        }
        return out + "\n" + Objects.toString(execResult, "");
    }

    private SlotStateDocument readSlotState(String json) {
        if (json == null || json.isBlank()) {
            return SlotStateDocument.builder().build();
        }
        try {
            return objectMapper.readValue(json, SlotStateDocument.class);
        } catch (Exception e) {
            return SlotStateDocument.builder().build();
        }
    }

    private String writeSlotState(SlotStateDocument d) {
        try {
            return objectMapper.writeValueAsString(d);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String writeJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String writePayloadJson(UiRequestPayload p) {
        try {
            return objectMapper.writeValueAsString(p);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
