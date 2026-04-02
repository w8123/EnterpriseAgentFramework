package com.enterprise.ai.pipeline.chunk.impl;

import com.enterprise.ai.pipeline.chunk.ChunkStrategy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 段落切分策略 — 按自然段落（连续换行）分割文本。
 *
 * <p>当单个段落超过 chunkSize 时，会退化为固定长度切分。
 * 短段落会合并到同一个 chunk 中（直到接近 chunkSize）。</p>
 */
@Component
public class ParagraphChunkStrategy implements ChunkStrategy {

    @Override
    public List<String> split(String text, int chunkSize, int overlap) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return chunks;
        }

        // 按双换行或单换行分段
        String[] paragraphs = text.split("\\n\\s*\\n|\\n");
        StringBuilder buffer = new StringBuilder();

        for (String para : paragraphs) {
            String trimmed = para.trim();
            if (trimmed.isEmpty()) continue;

            // 单段落超长：按固定长度切分
            if (trimmed.length() > chunkSize) {
                if (!buffer.isEmpty()) {
                    chunks.add(buffer.toString().trim());
                    buffer.setLength(0);
                }
                int step = Math.max(chunkSize - overlap, 1);
                for (int i = 0; i < trimmed.length(); i += step) {
                    int end = Math.min(i + chunkSize, trimmed.length());
                    chunks.add(trimmed.substring(i, end).trim());
                    if (end >= trimmed.length()) break;
                }
                continue;
            }

            // 合并短段落
            if (buffer.length() + trimmed.length() + 1 > chunkSize) {
                if (!buffer.isEmpty()) {
                    chunks.add(buffer.toString().trim());
                    buffer.setLength(0);
                }
            }
            if (!buffer.isEmpty()) {
                buffer.append("\n");
            }
            buffer.append(trimmed);
        }

        if (!buffer.isEmpty()) {
            chunks.add(buffer.toString().trim());
        }

        return chunks;
    }

    @Override
    public String getStrategyName() {
        return "paragraph";
    }
}
