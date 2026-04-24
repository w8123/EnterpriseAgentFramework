package com.enterprise.ai.pipeline.step;

import com.enterprise.ai.pipeline.PipelineContext;
import com.enterprise.ai.pipeline.PipelineException;
import com.enterprise.ai.pipeline.PipelineStep;
import com.enterprise.ai.pipeline.chunk.ChunkStrategy;
import com.enterprise.ai.pipeline.chunk.ChunkStrategyFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 步骤四：文本切分 — 根据策略将清洗后的文本切分为多个 chunk。
 *
 * <p>通过 {@link ChunkStrategyFactory} 根据 context 中的 chunkStrategy 名称
 * 动态选择切分策略（fixed_length / paragraph / semantic）。</p>
 *
 * <p>切分参数从 context 中读取：
 * <ul>
 *   <li>{@code chunkStrategy} — 策略名称</li>
 *   <li>{@code chunkSize} — 每个 chunk 的目标大小（字符数）</li>
 *   <li>{@code chunkOverlap} — 相邻 chunk 的重叠字符数</li>
 * </ul></p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChunkStep implements PipelineStep {

    private final ChunkStrategyFactory strategyFactory;

    @Override
    public void process(PipelineContext context) {
        String text = context.getCleanedText();
        if (text == null || text.isEmpty()) {
            throw new PipelineException(getName(), context.getFileId(), "清洗后文本为空，无法切分");
        }

        ChunkStrategy strategy = strategyFactory.getStrategy(context.getChunkStrategy());
        List<String> chunks = strategy.split(text, context.getChunkSize(), context.getChunkOverlap());

        if (chunks.isEmpty()) {
            throw new PipelineException(getName(), context.getFileId(), "切分结果为空");
        }

        context.setChunks(chunks);
        log.debug("ChunkStep 完成: fileId={}, 策略={}, chunk数量={}",
                context.getFileId(), context.getChunkStrategy(), chunks.size());
    }

    @Override
    public String getName() {
        return "CHUNK";
    }
}
