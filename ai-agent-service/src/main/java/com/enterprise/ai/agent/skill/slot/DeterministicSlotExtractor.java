package com.enterprise.ai.agent.skill.slot;

import com.enterprise.ai.agent.skill.interactive.FieldSpec;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 通用确定性槽位抽取器：先覆盖低歧义、低成本的时间 / 手机号 / 金额 / 数值。
 */
@Component
public class DeterministicSlotExtractor {

    private static final Pattern ISO_DATE = Pattern.compile("(20\\d{2})[-/.年](\\d{1,2})[-/.月](\\d{1,2})日?");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(1[3-9]\\d{9})(?!\\d)");
    private static final Pattern MONEY = Pattern.compile("(?<!\\d)(\\d+(?:\\.\\d{1,2})?)\\s*(?:元|块|人民币)");
    private static final Pattern NUMBER = Pattern.compile("(?<!\\d)(\\d+(?:\\.\\d+)?)(?!\\d)");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    public Optional<Object> extract(String userText, FieldSpec field) {
        if (userText == null || userText.isBlank() || field == null) {
            return Optional.empty();
        }
        String signature = ((field.getKey() == null ? "" : field.getKey()) + " "
                + (field.getLabel() == null ? "" : field.getLabel()) + " "
                + (field.getLlmExtractHint() == null ? "" : field.getLlmExtractHint()))
                .toLowerCase(Locale.ROOT);
        if (isDateField(field, signature)) {
            return extractDate(userText);
        }
        if (containsAny(signature, "phone", "mobile", "tel", "手机号", "电话", "联系方式")) {
            return firstGroup(PHONE, userText);
        }
        if (containsAny(signature, "money", "amount", "price", "金额", "费用", "价格")) {
            return firstGroup(MONEY, userText).map(DeterministicSlotExtractor::toNumberIfPossible);
        }
        if ("number".equalsIgnoreCase(field.getType())) {
            return firstGroup(NUMBER, userText).map(DeterministicSlotExtractor::toNumberIfPossible);
        }
        return Optional.empty();
    }

    private Optional<Object> extractDate(String text) {
        LocalDate today = LocalDate.now();
        if (text.contains("今天")) {
            return Optional.of(DATE.format(today));
        }
        if (text.contains("明天")) {
            return Optional.of(DATE.format(today.plusDays(1)));
        }
        if (text.contains("后天")) {
            return Optional.of(DATE.format(today.plusDays(2)));
        }
        if (text.contains("昨天")) {
            return Optional.of(DATE.format(today.minusDays(1)));
        }
        Matcher matcher = ISO_DATE.matcher(text);
        if (!matcher.find()) {
            return Optional.empty();
        }
        int year = Integer.parseInt(matcher.group(1));
        int month = Integer.parseInt(matcher.group(2));
        int day = Integer.parseInt(matcher.group(3));
        return Optional.of(DATE.format(LocalDate.of(year, month, day)));
    }

    private static boolean isDateField(FieldSpec field, String signature) {
        return "date".equalsIgnoreCase(field.getType())
                || containsAny(signature, "date", "time", "日期", "时间");
    }

    private static Optional<Object> firstGroup(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    private static Object toNumberIfPossible(Object raw) {
        String s = String.valueOf(raw);
        try {
            return s.contains(".") ? Double.parseDouble(s) : Long.parseLong(s);
        } catch (NumberFormatException ex) {
            return s;
        }
    }

    private static boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
