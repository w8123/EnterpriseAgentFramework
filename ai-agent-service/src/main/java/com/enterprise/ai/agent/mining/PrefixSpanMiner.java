package com.enterprise.ai.agent.mining;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MVP 版本：先用固定窗口 n-gram 近似频繁序列，后续可替换成 PrefixSpan。
 * <p>
 * 关键口径：
 * <ul>
 *   <li>support = 出现该模式的 <b>distinct trace</b> 数（和经典关联规则一致，
 *       不是滑窗命中次数），否则同一条长链会把自己打成"高频模式"；</li>
 *   <li>同一条 trace 内若 pattern 重复出现，只计 1；</li>
 *   <li>traceIds 去重，保持稳定顺序（供前端"来源链路"展示）。</li>
 * </ul>
 */
@Component
public class PrefixSpanMiner {

    private static final int WINDOW = 3;

    public List<ChainPattern> mine(List<ToolChainAggregator.ToolChain> chains, int minSupport) {
        Map<String, ChainPattern> patterns = new HashMap<>();
        for (ToolChainAggregator.ToolChain chain : chains) {
            List<String> seq = chain.sequence();
            if (seq == null || seq.size() < 2) {
                continue;
            }
            // 先统计本条 trace 内出现过的 pattern，同 chain 内重复只计 1
            Set<String> seenInChain = new HashSet<>();
            for (int i = 0; i <= seq.size() - 2; i++) {
                int end = Math.min(i + WINDOW, seq.size());
                List<String> gram = seq.subList(i, end);
                if (gram.size() < 2) {
                    continue;
                }
                String key = String.join(" -> ", gram);
                if (!seenInChain.add(key)) {
                    continue;
                }
                ChainPattern pattern = patterns.computeIfAbsent(key,
                        k -> new ChainPattern(new ArrayList<>(gram)));
                pattern.traceIds.add(chain.traceId());
            }
        }
        return patterns.values().stream()
                .filter(p -> p.support() >= minSupport)
                .sorted((a, b) -> Integer.compare(b.support(), a.support()))
                .toList();
    }

    public static class ChainPattern {
        private final List<String> sequence;
        /** LinkedHashSet：天然去重，同时 support = size 保持稳定顺序供展示。 */
        private final Set<String> traceIds = new LinkedHashSet<>();

        public ChainPattern(List<String> sequence) {
            this.sequence = sequence;
        }

        public List<String> sequence() { return sequence; }
        public int support() { return traceIds.size(); }
        public List<String> traceIds() { return new ArrayList<>(traceIds); }

        /** 对外追加 traceId（供 {@code SkillMiningService.extractDraftFromTrace} 从单条 trace 构造 pattern）。 */
        public void addTraceId(String traceId) {
            if (traceId != null && !traceId.isBlank()) {
                traceIds.add(traceId);
            }
        }
    }
}
