package com.enterprise.ai.agent.skill.slot.extractor;

import com.enterprise.ai.agent.skill.interactive.FieldSpec;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * SlotExtractor 注册中心。Spring 启动期自动收集容器中所有 {@link SlotExtractor} Bean。
 * <p>
 * 为了让前端"测试台"可以选择"只跑某个提取器"或"按 priority 全跑"，本类同时提供：
 * <ul>
 *     <li>{@link #all()}：管理端列表展示用；</li>
 *     <li>{@link #findApplicable(FieldSpec, ExtractContext)}：运行时按 priority 顺序得到适用集合；</li>
 *     <li>{@link #byName(String)}：测试台/单点测试。</li>
 * </ul>
 */
@Slf4j
@Component
public class SlotExtractorRegistry {

    private final List<SlotExtractor> extractors;
    private final Map<String, SlotExtractor> byName = new ConcurrentHashMap<>();

    public SlotExtractorRegistry(List<SlotExtractor> beans) {
        ArrayList<SlotExtractor> sorted = new ArrayList<>(beans == null ? List.of() : beans);
        sorted.sort(Comparator.comparingInt(SlotExtractor::priority));
        this.extractors = sorted;
    }

    @PostConstruct
    void init() {
        for (SlotExtractor ex : extractors) {
            if (ex.name() == null || ex.name().isBlank()) {
                throw new IllegalStateException("SlotExtractor name 不能为空: " + ex.getClass());
            }
            SlotExtractor prev = byName.put(ex.name(), ex);
            if (prev != null) {
                throw new IllegalStateException("SlotExtractor name 重复: " + ex.name());
            }
        }
        log.info("[SlotExtractorRegistry] 已注册 {} 个槽位提取器: {}",
                extractors.size(),
                extractors.stream().map(SlotExtractor::name).collect(Collectors.joining(", ")));
    }

    public List<SlotExtractor> all() {
        return List.copyOf(extractors);
    }

    public SlotExtractor byName(String name) {
        return byName.get(name);
    }

    /**
     * 按 priority 顺序返回 {@code accepts(field)} 为 true 的提取器列表。
     * 只读快照，调用方可以放心遍历。
     */
    public List<SlotExtractor> findApplicable(FieldSpec field, ExtractContext ctx) {
        if (field == null) return List.of();
        List<SlotExtractor> hits = new ArrayList<>();
        for (SlotExtractor ex : extractors) {
            try {
                if (ex.accepts(field, ctx)) {
                    hits.add(ex);
                }
            } catch (Exception e) {
                log.warn("[SlotExtractorRegistry] {}.accepts 抛异常，已忽略: {}", ex.name(), e.toString());
            }
        }
        return hits;
    }
}
