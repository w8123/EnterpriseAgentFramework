package com.enterprise.ai.pipeline.chunk.impl;

import com.enterprise.ai.pipeline.chunk.ChunkStrategy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 固定长度滑动窗口切分策略。
 *
 * <p>按 chunkSize 步长切分文本，相邻 chunk 之间保留 overlap 字符的重叠，
 * 确保上下文不会在切分边界丢失。</p>
 */
@Component
public class FixedLengthChunkStrategy implements ChunkStrategy {

    @Override
    public List<String> split(String text, int chunkSize, int overlap) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return chunks;
        }

        int step = Math.max(chunkSize - overlap, 1);
        int length = text.length();

        for (int i = 0; i < length; i += step) {
            int end = Math.min(i + chunkSize, length);
            String chunk = text.substring(i, end).trim();
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }
            if (end >= length) break;
        }

        return chunks;
    }

    @Override
    public String getStrategyName() {
        return "fixed_length";
    }
}
