package com.enterprise.ai.pipeline.chunk;

import java.util.List;

/**
 * 文本切分策略接口 — 定义将长文本切分为小块的算法。
 *
 * <p>内置三种策略：
 * <ul>
 *   <li>{@code fixed_length} — 固定长度滑动窗口</li>
 *   <li>{@code paragraph} — 按段落自然分割</li>
 *   <li>{@code semantic} — 语义感知切分（预留）</li>
 * </ul>
 * 扩展方式：实现此接口并注册到 {@link ChunkStrategyFactory}。</p>
 */
public interface ChunkStrategy {

    /**
     * 将文本按策略切分为多个 chunk
     *
     * @param text      待切分文本
     * @param chunkSize 切分大小参考值（字符数，部分策略可能忽略此参数）
     * @param overlap   重叠字符数
     * @return chunk 列表
     */
    List<String> split(String text, int chunkSize, int overlap);

    /**
     * 策略名称标识（与配置中的 chunkStrategy 值对应）
     */
    String getStrategyName();
}
