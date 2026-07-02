package com.enterprise.ai.pipeline.chunk;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 切分策略工厂 — 根据策略名称获取对应的 {@link ChunkStrategy} 实现。
 *
 * <p>Spring 容器启动时自动收集所有策略 Bean，新增策略只需实现接口并注册为 Bean。</p>
 */
@Slf4j
@Component
public class ChunkStrategyFactory {

    private final Map<String, ChunkStrategy> strategyMap = new HashMap<>();
    private static final String DEFAULT_STRATEGY = "fixed_length";

    public ChunkStrategyFactory(List<ChunkStrategy> strategies) {
        for (ChunkStrategy strategy : strategies) {
            strategyMap.put(strategy.getStrategyName(), strategy);
            log.debug("注册切分策略: {} → {}", strategy.getStrategyName(), strategy.getClass().getSimpleName());
        }
    }

    /**
     * 根据策略名称获取切分策略实例
     *
     * @param strategyName 策略名称（fixed_length / paragraph / semantic）
     * @return 策略实例，名称无效时回退到默认策略
     */
    public ChunkStrategy getStrategy(String strategyName) {
        ChunkStrategy strategy = strategyMap.get(strategyName);
        if (strategy == null) {
            log.warn("未找到切分策略: {}, 使用默认策略: {}", strategyName, DEFAULT_STRATEGY);
            return strategyMap.get(DEFAULT_STRATEGY);
        }
        return strategy;
    }

    public boolean hasStrategy(String strategyName) {
        return strategyMap.containsKey(strategyName);
    }
}
