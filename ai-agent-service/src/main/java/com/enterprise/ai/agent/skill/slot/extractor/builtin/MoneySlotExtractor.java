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
 * 金额提取器：识别"123 元 / 1.5 万 / 100 块 / 10000 人民币"。
 * 输出统一为 Long（整数）或 Double（带小数）。
 */
@Component
public class MoneySlotExtractor implements SlotExtractor {

    private static final Pattern WAN = Pattern.compile("(?<!\\d)(\\d+(?:\\.\\d{1,4})?)\\s*万\\s*(?:元|块|人民币)?");
    private static final Pattern PLAIN = Pattern.compile("(?<!\\d)(\\d+(?:\\.\\d{1,2})?)\\s*(?:元|块|人民币|RMB|rmb)");

    @Override
    public String name() { return "money"; }

    @Override
    public String displayName() { return "金额"; }

    @Override
    public int priority() { return 30; }

    @Override
    public boolean accepts(FieldSpec field, ExtractContext ctx) {
        if (field == null) return false;
        String sig = sig(field);
        return contains(sig, "money", "amount", "price", "cost", "金额", "费用", "价格", "报销", "支付");
    }

    @Override
    public Optional<SlotExtractResult> extract(String userText, FieldSpec field, ExtractContext ctx) {
        if (userText == null || userText.isBlank()) return Optional.empty();
        Matcher wan = WAN.matcher(userText);
        if (wan.find()) {
            double v = Double.parseDouble(wan.group(1)) * 10000d;
            Object value = (v == Math.floor(v)) ? (Object) (long) v : v;
            return Optional.of(SlotExtractResult.high(value, "matched 万: " + wan.group()));
        }
        Matcher plain = PLAIN.matcher(userText);
        if (plain.find()) {
            String raw = plain.group(1);
            Object value = raw.contains(".") ? Double.parseDouble(raw) : Long.parseLong(raw);
            return Optional.of(SlotExtractResult.high(value, "matched 金额: " + plain.group()));
        }
        return Optional.empty();
    }

    @Override
    public Map<String, Object> metadata() {
        return Map.of("samples", "100 元 / 1.5 万 / 1000 块 / 200 RMB");
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
