package com.enterprise.ai.agent.skill.interactive;

import com.enterprise.ai.agent.llm.LlmService;
import com.enterprise.ai.agent.skill.slot.DeterministicSlotExtractor;
import com.enterprise.ai.agent.skill.slot.extractor.ExtractContext;
import com.enterprise.ai.agent.skill.slot.extractor.SlotExtractResult;
import com.enterprise.ai.agent.skill.slot.extractor.SlotExtractor;
import com.enterprise.ai.agent.skill.slot.extractor.SlotExtractorRegistry;
import com.enterprise.ai.agent.skill.slot.log.SlotExtractLogService;
import com.enterprise.ai.agent.tool.log.ToolExecutionContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 槽位抽取：SPI 提取器优先，字典/选项精确与模糊匹配次之，LLM JSON 抽取兜底。
 * <p>Phase P1 起改造为基于 {@link SlotExtractorRegistry} 的 SPI 调度。</p>
 */
@Slf4j
@Service
public class SlotExtractionService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final double MIN_CONFIDENCE = 0.5;

    private final LlmService llmService;
    private final ObjectMapper objectMapper;
    private final DeterministicSlotExtractor deterministicSlotExtractor;
    private final SlotExtractorRegistry extractorRegistry;
    private final SlotExtractLogService slotExtractLogService;

    @Autowired
    public SlotExtractionService(LlmService llmService,
                                 ObjectMapper objectMapper,
                                 DeterministicSlotExtractor deterministicSlotExtractor,
                                 SlotExtractorRegistry extractorRegistry,
                                 SlotExtractLogService slotExtractLogService) {
        this.llmService = llmService;
        this.objectMapper = objectMapper;
        this.deterministicSlotExtractor = deterministicSlotExtractor;
        this.extractorRegistry = extractorRegistry;
        this.slotExtractLogService = slotExtractLogService;
    }

    public SlotExtractionService(LlmService llmService, ObjectMapper objectMapper) {
        this(llmService, objectMapper, new DeterministicSlotExtractor(), null, null);
    }

    /**
     * 将用户自然语言合并到 slots（仅处理 spec 中声明的 key）。
     */
    public void mergeFromUserText(Map<String, Object> slots,
                                  String userText,
                                  InteractiveFormSpec spec,
                                  Map<String, List<FieldOptionSpec>> optionsByField) {
        mergeFromUserText(slots, userText, spec, optionsByField, null, null);
    }

    /**
     * 同上，新增 skillName + ToolExecutionContext，用于 SlotExtractor SPI 上下文与日志归并。
     * 若 {@code skillName} 或 {@code ctx} 为空，会退化为旧路径但仍走 SPI 注册中心。
     */
    public void mergeFromUserText(Map<String, Object> slots,
                                  String userText,
                                  InteractiveFormSpec spec,
                                  Map<String, List<FieldOptionSpec>> optionsByField,
                                  String skillName,
                                  ToolExecutionContext ctx) {
        if (userText == null || userText.isBlank() || spec.getFields() == null) {
            return;
        }
        ExtractContext extractCtx = buildExtractContext(ctx);
        String traceId = ctx == null ? null : ctx.getTraceId();

        // 1) SPI 提取器：按 priority 顺序，命中即停
        for (InteractiveFormFieldTree.LeafBinding lb : InteractiveFormFieldTree.flattenLeaves(spec.getFields(), List.of())) {
            FieldSpec field = lb.field();
            if (slots.containsKey(field.getKey()) && slots.get(field.getKey()) != null) {
                continue;
            }
            if (extractorRegistry == null) {
                deterministicSlotExtractor.extract(userText, field)
                        .ifPresent(value -> slots.put(field.getKey(), value));
                continue;
            }
            List<String> binding = slotExtractLogService == null
                    ? null : slotExtractLogService.resolveBinding(skillName, field.getKey());
            for (SlotExtractor ex : extractorRegistry.findApplicable(field, extractCtx)) {
                if (binding != null && !binding.isEmpty() && !binding.contains(ex.name())) {
                    continue;
                }
                long t0 = System.currentTimeMillis();
                Optional<SlotExtractResult> result = safeExtract(ex, userText, field, extractCtx);
                long elapsed = System.currentTimeMillis() - t0;
                if (slotExtractLogService != null) {
                    slotExtractLogService.recordAsync(traceId, skillName, field.getKey(),
                            ex.name(), userText, result, elapsed);
                }
                if (result.isPresent() && result.get().confidence() >= MIN_CONFIDENCE
                        && result.get().value() != null) {
                    slots.put(field.getKey(), result.get().value());
                    break;
                }
            }
        }
        // 2) 确定性：对有候选项的字段做 label / value 匹配（仅叶子）
        for (InteractiveFormFieldTree.LeafBinding lb : InteractiveFormFieldTree.flattenLeaves(spec.getFields(), List.of())) {
            FieldSpec field = lb.field();
            if (slots.containsKey(field.getKey()) && slots.get(field.getKey()) != null) {
                continue;
            }
            List<FieldOptionSpec> opts = optionsByField.getOrDefault(field.getKey(), List.of());
            String hit = matchOptionFromText(userText, opts);
            if (hit != null) {
                slots.put(field.getKey(), hit);
            }
        }
        // 3) LLM 抽取剩余空槽
        Map<String, Object> llmSlots = extractWithLlm(userText, spec, optionsByField, slots);
        for (Map.Entry<String, Object> e : llmSlots.entrySet()) {
            if (e.getValue() == null) {
                continue;
            }
            if (!slots.containsKey(e.getKey()) || slots.get(e.getKey()) == null) {
                slots.put(e.getKey(), e.getValue());
            }
        }
    }

    private String matchOptionFromText(String userText, List<FieldOptionSpec> opts) {
        if (opts == null || opts.isEmpty()) {
            return null;
        }
        String lower = userText.toLowerCase(Locale.ROOT);
        for (FieldOptionSpec o : opts) {
            if (o.getLabel() != null && userText.contains(o.getLabel())) {
                return o.getValue();
            }
            if (o.getValue() != null && lower.contains(o.getValue().toLowerCase(Locale.ROOT))) {
                return o.getValue();
            }
        }
        // 前缀 / 包含弱匹配 label
        for (FieldOptionSpec o : opts) {
            if (o.getLabel() == null) {
                continue;
            }
            for (String token : userText.replace("，", ",").split("[,，\\s]+")) {
                if (token.isBlank()) {
                    continue;
                }
                if (o.getLabel().startsWith(token) || token.startsWith(o.getLabel())) {
                    return o.getValue();
                }
            }
        }
        return null;
    }

    private Map<String, Object> extractWithLlm(String userText,
                                                InteractiveFormSpec spec,
                                                Map<String, List<FieldOptionSpec>> optionsByField,
                                                Map<String, Object> already) {
        StringBuilder sb = new StringBuilder();
        sb.append("字段列表（只输出 JSON 对象，key 为字段 key，value 为字符串或数字，无法确定的 key 不要输出或填 null）：\n");
        for (InteractiveFormFieldTree.LeafBinding lb : InteractiveFormFieldTree.flattenLeaves(spec.getFields(), List.of())) {
            FieldSpec f = lb.field();
            if (already.get(f.getKey()) != null) {
                continue;
            }
            sb.append("- ").append(f.getKey()).append(" (").append(f.getLabel()).append(") 类型=").append(f.getType());
            if (f.getLlmExtractHint() != null && !f.getLlmExtractHint().isBlank()) {
                sb.append(" 提示:").append(f.getLlmExtractHint());
            }
            List<FieldOptionSpec> opts = optionsByField.get(f.getKey());
            if (opts != null && !opts.isEmpty()) {
                sb.append(" 合法取值: ");
                for (FieldOptionSpec o : opts) {
                    sb.append(o.getLabel()).append("=").append(o.getValue()).append("; ");
                }
            }
            sb.append("\n");
        }
        String system = """
                你是企业业务表单的槽位抽取器。只输出一个 JSON 对象，不要 markdown，不要解释。
                枚举类字段必须输出 spec 中给出的合法 value（英文大写等），不要自造。
                """;
        try {
            String raw = llmService.chat(system, "用户原话：\n" + userText + "\n\n" + sb);
            return parseJsonObjectLoose(raw);
        } catch (Exception ex) {
            log.debug("[SlotExtraction] LLM 抽取失败（忽略）: {}", ex.toString());
            return Map.of();
        }
    }

    Map<String, Object> parseJsonObjectLoose(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        String s = raw.trim();
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            if (nl > 0) {
                s = s.substring(nl + 1);
            }
            int end = s.lastIndexOf("```");
            if (end > 0) {
                s = s.substring(0, end).trim();
            }
        }
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return Map.of();
        }
        s = s.substring(start, end + 1);
        try {
            return objectMapper.readValue(s, MAP_TYPE);
        } catch (Exception ex) {
            log.debug("[SlotExtraction] JSON 解析失败: {}", ex.toString());
            return Map.of();
        }
    }

    private ExtractContext buildExtractContext(ToolExecutionContext ctx) {
        if (ctx == null) {
            return ExtractContext.anonymous();
        }
        return ExtractContext.builder()
                .userId(ctx.getUserId())
                .userDeptId(null)
                .traceId(ctx.getTraceId())
                .now(LocalDateTime.now())
                .sessionVars(Map.of())
                .build();
    }

    private Optional<SlotExtractResult> safeExtract(SlotExtractor extractor, String userText,
                                                    FieldSpec field, ExtractContext ctx) {
        try {
            return extractor.extract(userText, field, ctx);
        } catch (Exception ex) {
            log.warn("[SlotExtraction] 提取器 {} 抛异常，已忽略: {}", extractor.name(), ex.toString());
            return Optional.empty();
        }
    }

    public String validateField(FieldSpec field, Object value) {
        if (value == null) {
            return field.isRequired() ? "必填" : null;
        }
        String str = String.valueOf(value).trim();
        if (str.isEmpty() && field.isRequired()) {
            return "不能为空";
        }
        if (field.getValidateRegex() != null && !field.getValidateRegex().isBlank()) {
            if (!Pattern.compile(field.getValidateRegex()).matcher(str).matches()) {
                return "格式不符合要求";
            }
        }
        if ("number".equalsIgnoreCase(field.getType())) {
            try {
                Double.parseDouble(str);
            } catch (NumberFormatException e) {
                return "需要数字";
            }
        }
        return null;
    }
}
