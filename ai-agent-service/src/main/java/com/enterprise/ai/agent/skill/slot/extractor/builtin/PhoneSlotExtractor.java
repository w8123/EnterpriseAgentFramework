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
 * 大陆 11 位手机号提取器；命中位置不被前后数字包裹，避免误抓订单号 / 身份证片段。
 */
@Component
public class PhoneSlotExtractor implements SlotExtractor {

    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(1[3-9]\\d{9})(?!\\d)");

    @Override
    public String name() { return "phone"; }

    @Override
    public String displayName() { return "手机号"; }

    @Override
    public int priority() { return 20; }

    @Override
    public boolean accepts(FieldSpec field, ExtractContext ctx) {
        if (field == null) return false;
        String sig = sig(field);
        return contains(sig, "phone", "mobile", "tel", "手机", "电话", "联系");
    }

    @Override
    public Optional<SlotExtractResult> extract(String userText, FieldSpec field, ExtractContext ctx) {
        if (userText == null || userText.isBlank()) return Optional.empty();
        Matcher m = PHONE.matcher(userText);
        if (m.find()) {
            return Optional.of(SlotExtractResult.high(m.group(1), "matched 11-digit mobile: " + m.group(1)));
        }
        return Optional.empty();
    }

    @Override
    public Map<String, Object> metadata() {
        return Map.of("regex", PHONE.pattern(), "samples", "13800138000");
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
