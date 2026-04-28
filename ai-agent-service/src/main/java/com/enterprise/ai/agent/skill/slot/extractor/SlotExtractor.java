package com.enterprise.ai.agent.skill.slot.extractor;

import com.enterprise.ai.agent.skill.interactive.FieldSpec;

import java.util.Map;
import java.util.Optional;

/**
 * 槽位提取器 SPI。
 * <p>
 * 实现类只要在 Spring 容器中暴露为 {@code @Component}，{@link SlotExtractorRegistry}
 * 就会在启动期收集并按 {@link #priority()} 排序。运行时由 {@code SlotExtractionService}
 * 在 LLM 兜底前依次询问每个适用提取器。
 * <p>
 * 设计要点：
 * <ul>
 *     <li>提取器只要"能识别就给值"，不能识别返回 {@link Optional#empty()}；</li>
 *     <li>{@link #accepts(FieldSpec, ExtractContext)} 在判定阶段调用，{@link #extract} 才真正跑算法；</li>
 *     <li>{@link SlotExtractResult#confidence()} 必须 0..1，低于 {@code MIN_CONFIDENCE} 视作未命中。</li>
 * </ul>
 */
public interface SlotExtractor {

    /** 全局唯一名称，建议小写蛇形：time / dept / user / id_card / address。 */
    String name();

    /** 给前端展示的中文名。 */
    String displayName();

    /** 优先级数值越小越先尝试，缺省值 100。 */
    default int priority() { return 100; }

    /**
     * 判断当前提取器是否适合处理给定 {@link FieldSpec}。
     * 通常按 {@code field.type} / {@code field.key} / {@code field.label} 关键词命中。
     */
    boolean accepts(FieldSpec field, ExtractContext ctx);

    /**
     * 真正跑算法。返回 {@link Optional#empty()} 表示未识别，调用方将继续尝试下一个提取器。
     */
    Optional<SlotExtractResult> extract(String userText, FieldSpec field, ExtractContext ctx);

    /** 给前端"提取器列表"展示的元数据，例如适用的 field.type / 关键词命中规则等。 */
    default Map<String, Object> metadata() { return Map.of(); }
}
