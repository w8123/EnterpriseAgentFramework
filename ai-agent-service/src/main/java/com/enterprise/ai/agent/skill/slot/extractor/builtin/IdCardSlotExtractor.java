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
 * 18 位中国大陆身份证号提取器，含 GB 11643 模 11 校验位。
 * 仅识别强校验通过的号段，避免把订单号 / 流水号误识别成身份证。
 */
@Component
public class IdCardSlotExtractor implements SlotExtractor {

    private static final Pattern ID = Pattern.compile("(?<!\\d)(\\d{17}[\\dXx])(?!\\d)");
    private static final int[] WEIGHTS = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};
    private static final char[] CHECKSUMS = {'1', '0', 'X', '9', '8', '7', '6', '5', '4', '3', '2'};

    @Override
    public String name() { return "id_card"; }

    @Override
    public String displayName() { return "身份证号"; }

    @Override
    public int priority() { return 25; }

    @Override
    public boolean accepts(FieldSpec field, ExtractContext ctx) {
        if (field == null) return false;
        String sig = sig(field);
        return contains(sig, "idcard", "id_card", "身份证");
    }

    @Override
    public Optional<SlotExtractResult> extract(String userText, FieldSpec field, ExtractContext ctx) {
        if (userText == null || userText.isBlank()) return Optional.empty();
        Matcher m = ID.matcher(userText);
        while (m.find()) {
            String s = m.group(1).toUpperCase(Locale.ROOT);
            if (validate(s)) {
                return Optional.of(SlotExtractResult.high(s, "matched 身份证 (校验位通过): " + s));
            }
        }
        return Optional.empty();
    }

    static boolean validate(String idCard) {
        if (idCard == null || idCard.length() != 18) return false;
        int sum = 0;
        for (int i = 0; i < 17; i++) {
            char c = idCard.charAt(i);
            if (c < '0' || c > '9') return false;
            sum += (c - '0') * WEIGHTS[i];
        }
        char expected = CHECKSUMS[sum % 11];
        return expected == idCard.charAt(17);
    }

    @Override
    public Map<String, Object> metadata() {
        return Map.of("validation", "GB 11643 mod-11", "samples", "11010519491231002X");
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
