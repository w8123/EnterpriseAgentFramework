package com.enterprise.ai.agent.skill.slot.extractor.builtin;

import com.enterprise.ai.agent.skill.interactive.FieldSpec;
import com.enterprise.ai.agent.skill.slot.extractor.ExtractContext;
import com.enterprise.ai.agent.skill.slot.extractor.SlotExtractResult;
import com.enterprise.ai.agent.skill.slot.extractor.SlotExtractor;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 地址提取器（轻量）：识别"X 省 X 市 X 区 + 详细地址"模式。
 * <p>不内置全国行政区划字典——那是单独的工程。本提取器只做"看起来像地址"的规则识别，
 * 把首尾切掉常见冗余前后缀后整体作为地址原文返回。</p>
 */
@Component
public class AddressSlotExtractor implements SlotExtractor {

    private static final Pattern PROV_CITY = Pattern.compile(
            "([\\u4e00-\\u9fa5]{2,8}(?:省|自治区))?\\s*([\\u4e00-\\u9fa5]{2,10}(?:市|盟|州|地区))" +
            "\\s*([\\u4e00-\\u9fa5]{2,10}(?:区|县|旗))?[\\u4e00-\\u9fa5\\d\\s\\-#号路街道里巷弄座栋\\(\\)（）/]{2,80}"
    );

    @Override
    public String name() { return "address"; }

    @Override
    public String displayName() { return "地址"; }

    @Override
    public int priority() { return 60; }

    @Override
    public boolean accepts(FieldSpec field, ExtractContext ctx) {
        if (field == null) return false;
        String sig = sig(field);
        return contains(sig, "address", "addr", "地址", "收件", "寄送");
    }

    @Override
    public Optional<SlotExtractResult> extract(String userText, FieldSpec field, ExtractContext ctx) {
        if (userText == null || userText.isBlank()) return Optional.empty();
        Matcher m = PROV_CITY.matcher(userText);
        if (!m.find()) return Optional.empty();
        String hit = m.group().trim();
        if (hit.length() < 5) return Optional.empty();
        return Optional.of(SlotExtractResult.of(hit, 0.7,
                "matched 行政区域 + 详细地址: " + hit));
    }

    @Override
    public Map<String, Object> metadata() {
        return Map.of("samples", "北京市朝阳区建国路 88 号 SOHO 现代城 / 浙江省杭州市余杭区文一西路 969 号");
    }

    private static String sig(FieldSpec f) {
        return ((f.getKey() == null ? "" : f.getKey()) + " "
                + (f.getLabel() == null ? "" : f.getLabel()) + " "
                + (f.getLlmExtractHint() == null ? "" : f.getLlmExtractHint()))
                .toLowerCase(Locale.ROOT);
    }

    private static boolean contains(String t, String... needles) {
        for (String n : needles) if (t.contains(n)) return true;
        return false;
    }
}
