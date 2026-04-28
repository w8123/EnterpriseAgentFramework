package com.enterprise.ai.agent.skill.slot.extractor;

/**
 * 单次提取结果。
 *
 * @param value      提取出的槽位值（已规范化为目标类型，date 为 ISO 字符串、number 为 Long/Double）
 * @param confidence 0..1，1 = 极高置信
 * @param evidence   人类可读的证据描述，会落库 {@code slot_extract_log.evidence} 给排查用
 */
public record SlotExtractResult(Object value, double confidence, String evidence) {

    public static SlotExtractResult of(Object value, double confidence, String evidence) {
        return new SlotExtractResult(value, confidence, evidence);
    }

    /** 高置信便捷工厂（0.95），用于字典精确匹配 / 强校验通过的场景。 */
    public static SlotExtractResult high(Object value, String evidence) {
        return new SlotExtractResult(value, 0.95, evidence);
    }
}
