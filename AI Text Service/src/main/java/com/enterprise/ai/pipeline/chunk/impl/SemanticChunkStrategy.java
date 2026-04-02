package com.enterprise.ai.pipeline.chunk.impl;

import com.enterprise.ai.pipeline.chunk.ChunkStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 语义感知切分策略（预留扩展）。
 *
 * <p>当前实现：基于句子边界的智能切分（按句号、问号、感叹号断句），
 * 在 chunkSize 范围内尽量保持语义完整性。</p>
 *
 * <p>未来可增强为：
 * <ul>
 *   <li>基于 Embedding 余弦相似度判断语义边界</li>
 *   <li>基于标题层级结构切分</li>
 *   <li>结合 NLP 分句模型</li>
 * </ul></p>
 */
@Slf4j
@Component
public class SemanticChunkStrategy implements ChunkStrategy {

    private static final Pattern SENTENCE_END = Pattern.compile("[。！？.!?]+");

    @Override
    public List<String> split(String text, int chunkSize, int overlap) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return chunks;
        }

        // 先按句子拆分
        List<String> sentences = splitBySentence(text);
        StringBuilder buffer = new StringBuilder();

        for (String sentence : sentences) {
            if (buffer.length() + sentence.length() > chunkSize && !buffer.isEmpty()) {
                chunks.add(buffer.toString().trim());

                // 保留 overlap：从 buffer 末尾回退 overlap 字符作为下一个 chunk 的开头
                String bufferStr = buffer.toString();
                buffer.setLength(0);
                if (overlap > 0 && bufferStr.length() > overlap) {
                    buffer.append(bufferStr.substring(bufferStr.length() - overlap));
                }
            }
            buffer.append(sentence);
        }

        if (!buffer.isEmpty()) {
            chunks.add(buffer.toString().trim());
        }

        return chunks;
    }

    @Override
    public String getStrategyName() {
        return "semantic";
    }

    private List<String> splitBySentence(String text) {
        List<String> sentences = new ArrayList<>();
        int start = 0;
        var matcher = SENTENCE_END.matcher(text);
        while (matcher.find()) {
            int end = matcher.end();
            sentences.add(text.substring(start, end));
            start = end;
        }
        if (start < text.length()) {
            sentences.add(text.substring(start));
        }
        return sentences;
    }
}
