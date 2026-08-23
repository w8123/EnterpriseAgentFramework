package com.enterprise.ai.pipeline.step;

import com.enterprise.ai.pipeline.PipelineContext;
import com.enterprise.ai.pipeline.PipelineException;
import com.enterprise.ai.pipeline.PipelineStep;
import com.enterprise.ai.pipeline.chunk.ChunkStrategy;
import com.enterprise.ai.pipeline.chunk.ChunkStrategyFactory;
import com.enterprise.ai.pipeline.document.DocumentChunkSource;
import com.enterprise.ai.pipeline.document.ParsedDocumentElement;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
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
        List<String> chunks = new ArrayList<>();
        List<DocumentChunkSource> sources = new ArrayList<>();

        List<ParsedDocumentElement> elements = context.getParsedDocument() == null
                ? List.of() : context.getParsedDocument().getElements();
        if (elements != null && !elements.isEmpty()) {
            elements.stream()
                    .filter(element -> element.getText() != null && !element.getText().isBlank())
                    .sorted(Comparator.comparingInt(ParsedDocumentElement::getOrder))
                    .forEach(element -> appendElementChunks(strategy, context, chunks, sources, element));
        }
        if (chunks.isEmpty()) {
            List<String> fallbackChunks = strategy.split(text, context.getChunkSize(), context.getChunkOverlap());
            chunks.addAll(fallbackChunks);
            for (int index = 0; index < fallbackChunks.size(); index++) {
                sources.add(DocumentChunkSource.builder().elementType("TEXT").build());
            }
        }

        if (chunks.isEmpty()) {
            throw new PipelineException(getName(), context.getFileId(), "切分结果为空");
        }

        context.setChunks(chunks);
        context.setChunkSources(sources);
        log.debug("ChunkStep 完成: fileId={}, 策略={}, chunk数量={}",
                context.getFileId(), context.getChunkStrategy(), chunks.size());
    }

    @Override
    public String getName() {
        return "CHUNK";
    }

    private static void appendElementChunks(ChunkStrategy strategy, PipelineContext context,
                                            List<String> chunks, List<DocumentChunkSource> sources,
                                            ParsedDocumentElement element) {
        List<String> elementChunks = strategy.split(element.getText(), context.getChunkSize(), context.getChunkOverlap());
        for (String elementChunk : elementChunks) {
            if (elementChunk == null || elementChunk.isBlank()) {
                continue;
            }
            chunks.add(elementChunk);
            sources.add(DocumentChunkSource.builder()
                    .elementType(element.getType())
                    .sectionPath(element.getSectionPath())
                    .sourceLocator(element.getSourceLocator())
                    .build());
        }
    }
}
