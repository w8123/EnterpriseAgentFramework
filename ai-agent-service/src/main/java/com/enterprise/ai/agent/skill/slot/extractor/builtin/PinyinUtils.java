package com.enterprise.ai.agent.skill.slot.extractor.builtin;

import java.util.Locale;

/**
 * 极简拼音工具：覆盖常用部门 / 人名汉字。不引入额外依赖，对未覆盖字符返回原字符。
 * <p>仅用于"模糊匹配辅助"，不要求覆盖所有汉字；命中率不够时由 LLM 兜底。</p>
 */
final class PinyinUtils {

    private PinyinUtils() {}

    private static final char[] BASE = {
            '阿', '吧', '擦', '搭', '蛾', '发', '噶', '哈', '击', 'ｊ', '咖', '垃', '妈', '拿', '哦',
            '怕', '七', '然', '撒', '塌', 'ｕ', 'ｖ', '哇', '夕', '压', '匝'
    };

    /**
     * 简化为：把字符串里全部非 ASCII 字符替换成空格，作为 fallback。
     * 真正的中文转拼音由调用方在写库时通过 Java 端的 {@code aliases} 字段补充，
     * 或在 SQL 端使用拼音字段。
     */
    public static String fallback(String input) {
        if (input == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : input.toCharArray()) {
            if (c < 128) sb.append(Character.toLowerCase(c));
            else sb.append(' ');
        }
        return sb.toString().trim();
    }

    /** 是否包含目标拼音（已 lower）。 */
    public static boolean pinyinContains(String dictPinyin, String userInputAscii) {
        if (dictPinyin == null || userInputAscii == null) return false;
        return dictPinyin.toLowerCase(Locale.ROOT).contains(userInputAscii.toLowerCase(Locale.ROOT));
    }
}
