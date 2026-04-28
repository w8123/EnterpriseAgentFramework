package com.enterprise.ai.agent.skill.slot.extractor.builtin;

import com.enterprise.ai.agent.skill.interactive.FieldSpec;
import com.enterprise.ai.agent.skill.slot.extractor.ExtractContext;
import com.enterprise.ai.agent.skill.slot.extractor.SlotExtractResult;
import com.enterprise.ai.agent.skill.slot.extractor.SlotExtractor;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 时间提取器：在原 {@code DeterministicSlotExtractor} 仅"今天 / 明天 / 后天 / 昨天 / ISO" 之上，
 * 扩展中文相对时间表达式：
 * <ul>
 *     <li>"上周一 / 上周三 / 本周五 / 下周二"</li>
 *     <li>"上个月底 / 这个月初 / 下个月 N 号"</li>
 *     <li>"X 天前 / X 天后 / 两周后 / 三个月前"</li>
 *     <li>"X 月 Y 日" / "Y 号"（缺省年取 {@link ExtractContext#now()} 的年份）</li>
 * </ul>
 * 输出统一为 ISO date {@code yyyy-MM-dd}。
 */
@Component
public class TimeSlotExtractor implements SlotExtractor {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;

    private static final Pattern ISO_DATE = Pattern.compile("(20\\d{2})[-/.年](\\d{1,2})[-/.月](\\d{1,2})日?");
    private static final Pattern WEEKDAY = Pattern.compile("(上上|上|本|这|下下|下)(?:个)?(?:周|星期|礼拜)\\s*([一二三四五六日天])");
    private static final Pattern N_UNIT_AGO_LATER = Pattern.compile("(\\d+|两|三|四|五|六|七|八|九|十)\\s*(天|日|周|星期|礼拜|月|个月)\\s*(前|后|以后|以前)");
    private static final Pattern MONTH_DAY = Pattern.compile("(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*[日号]");
    private static final Pattern DAY_OF_MONTH = Pattern.compile("(?<![\\d月])(\\d{1,2})\\s*[日号]");
    private static final Pattern MONTH_END_OR_BEGIN = Pattern.compile("(上上|上|本|这|下下|下)?(?:个)?月\\s*(初|底|末)");

    private static final Map<Character, DayOfWeek> WEEKDAY_MAP = Map.of(
            '一', DayOfWeek.MONDAY, '二', DayOfWeek.TUESDAY, '三', DayOfWeek.WEDNESDAY,
            '四', DayOfWeek.THURSDAY, '五', DayOfWeek.FRIDAY, '六', DayOfWeek.SATURDAY,
            '日', DayOfWeek.SUNDAY, '天', DayOfWeek.SUNDAY
    );

    @Override
    public String name() { return "time"; }

    @Override
    public String displayName() { return "时间 / 日期"; }

    @Override
    public int priority() { return 10; }

    @Override
    public boolean accepts(FieldSpec field, ExtractContext ctx) {
        if (field == null) return false;
        if ("date".equalsIgnoreCase(field.getType())) return true;
        String sig = signature(field);
        return containsAny(sig, "date", "time", "日期", "时间", "起始", "截止", "开始", "结束", "出生");
    }

    @Override
    public Optional<SlotExtractResult> extract(String userText, FieldSpec field, ExtractContext ctx) {
        if (userText == null || userText.isBlank()) return Optional.empty();
        LocalDate base = ctx == null || ctx.now() == null ? LocalDate.now() : ctx.now().toLocalDate();

        // 1) 关键字 - 高置信
        if (userText.contains("今天")) return Optional.of(SlotExtractResult.high(ISO.format(base), "matched 今天"));
        if (userText.contains("明天")) return Optional.of(SlotExtractResult.high(ISO.format(base.plusDays(1)), "matched 明天"));
        if (userText.contains("后天")) return Optional.of(SlotExtractResult.high(ISO.format(base.plusDays(2)), "matched 后天"));
        if (userText.contains("大后天")) return Optional.of(SlotExtractResult.high(ISO.format(base.plusDays(3)), "matched 大后天"));
        if (userText.contains("昨天")) return Optional.of(SlotExtractResult.high(ISO.format(base.minusDays(1)), "matched 昨天"));
        if (userText.contains("前天")) return Optional.of(SlotExtractResult.high(ISO.format(base.minusDays(2)), "matched 前天"));

        // 2) ISO 日期
        Matcher iso = ISO_DATE.matcher(userText);
        if (iso.find()) {
            try {
                LocalDate d = LocalDate.of(
                        Integer.parseInt(iso.group(1)),
                        Integer.parseInt(iso.group(2)),
                        Integer.parseInt(iso.group(3)));
                return Optional.of(SlotExtractResult.of(ISO.format(d), 0.98, "matched ISO: " + iso.group()));
            } catch (Exception ignored) { /* fallthrough */ }
        }

        // 3) "上周三 / 下周二"
        Matcher w = WEEKDAY.matcher(userText);
        if (w.find()) {
            int weekDelta = parseRelativeWeek(w.group(1));
            DayOfWeek target = WEEKDAY_MAP.get(w.group(2).charAt(0));
            if (target != null) {
                LocalDate monday = base.minusDays(base.getDayOfWeek().getValue() - 1);
                LocalDate result = monday.plusWeeks(weekDelta).plusDays(target.getValue() - 1);
                return Optional.of(SlotExtractResult.of(ISO.format(result), 0.9,
                        "matched 相对周: " + w.group() + " -> " + ISO.format(result)));
            }
        }

        // 4) "X 天前 / 两周后 / 三个月后"
        Matcher n = N_UNIT_AGO_LATER.matcher(userText);
        if (n.find()) {
            int amount = chineseNumberToInt(n.group(1));
            String unit = n.group(2);
            String dir = n.group(3);
            int sign = (dir.startsWith("前") || dir.startsWith("以前")) ? -1 : 1;
            LocalDate result = switch (unit) {
                case "天", "日" -> base.plusDays((long) sign * amount);
                case "周", "星期", "礼拜" -> base.plusWeeks((long) sign * amount);
                case "月", "个月" -> base.plusMonths((long) sign * amount);
                default -> null;
            };
            if (result != null) {
                return Optional.of(SlotExtractResult.of(ISO.format(result), 0.9,
                        "matched 相对量: " + n.group() + " -> " + ISO.format(result)));
            }
        }

        // 5) 月初 / 月底 + 上下月限定词
        Matcher me = MONTH_END_OR_BEGIN.matcher(userText);
        if (me.find()) {
            int monthOffset = parseRelativeMonthScope(me.group(1));
            String pos = me.group(2);
            LocalDate firstOfMonth = base.withDayOfMonth(1).plusMonths(monthOffset);
            LocalDate result = "初".equals(pos)
                    ? firstOfMonth
                    : firstOfMonth.plusMonths(1).minusDays(1);
            return Optional.of(SlotExtractResult.of(ISO.format(result), 0.85,
                    "matched 月初/底: " + me.group() + " -> " + ISO.format(result)));
        }

        // 6) "X 月 Y 日"
        Matcher md = MONTH_DAY.matcher(userText);
        if (md.find()) {
            try {
                LocalDate d = LocalDate.of(base.getYear(),
                        Integer.parseInt(md.group(1)),
                        Integer.parseInt(md.group(2)));
                return Optional.of(SlotExtractResult.of(ISO.format(d), 0.85,
                        "matched 月日: " + md.group() + " -> " + ISO.format(d)));
            } catch (Exception ignored) { /* fallthrough */ }
        }

        // 7) "Y 号"（取本月或下月，按是否已过该日选择）
        Matcher dom = DAY_OF_MONTH.matcher(userText);
        if (dom.find()) {
            try {
                int day = Integer.parseInt(dom.group(1));
                LocalDate thisMonth = base.withDayOfMonth(Math.min(day, base.lengthOfMonth()));
                LocalDate target = thisMonth.isBefore(base)
                        ? base.plusMonths(1).withDayOfMonth(Math.min(day, base.plusMonths(1).lengthOfMonth()))
                        : thisMonth;
                return Optional.of(SlotExtractResult.of(ISO.format(target), 0.7,
                        "matched 单日: " + dom.group() + " -> " + ISO.format(target)));
            } catch (Exception ignored) { /* fallthrough */ }
        }

        return Optional.empty();
    }

    @Override
    public Map<String, Object> metadata() {
        return Map.of(
                "appliesToTypes", "date",
                "keywords", "时间 / 日期 / 起始 / 截止 / 开始 / 结束",
                "samples", "今天 / 明天 / 上周三 / 下个月 15 号 / 两周后 / 月底 / 2026-04-28"
        );
    }

    private static int parseRelativeWeek(String prefix) {
        if ("上上".equals(prefix)) return -2;
        if ("上".equals(prefix)) return -1;
        if ("下".equals(prefix)) return 1;
        if ("下下".equals(prefix)) return 2;
        return 0;
    }

    private static int parseRelativeMonthScope(String prefix) {
        if (prefix == null) return 0;
        return switch (prefix) {
            case "上上" -> -2;
            case "上" -> -1;
            case "下" -> 1;
            case "下下" -> 2;
            default -> 0;
        };
    }

    private static int chineseNumberToInt(String s) {
        if (s == null) return 0;
        String t = s.trim();
        try {
            return Integer.parseInt(t);
        } catch (NumberFormatException ignored) {
            return switch (t) {
                case "两" -> 2;
                case "三" -> 3;
                case "四" -> 4;
                case "五" -> 5;
                case "六" -> 6;
                case "七" -> 7;
                case "八" -> 8;
                case "九" -> 9;
                case "十" -> 10;
                default -> 0;
            };
        }
    }

    private static String signature(FieldSpec field) {
        return ((field.getKey() == null ? "" : field.getKey()) + " "
                + (field.getLabel() == null ? "" : field.getLabel()) + " "
                + (field.getLlmExtractHint() == null ? "" : field.getLlmExtractHint()))
                .toLowerCase(Locale.ROOT);
    }

    private static boolean containsAny(String text, String... needles) {
        for (String n : needles) {
            if (text.contains(n)) return true;
        }
        return false;
    }
}
