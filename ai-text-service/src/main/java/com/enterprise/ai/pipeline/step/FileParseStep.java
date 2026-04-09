package com.enterprise.ai.pipeline.step;

import com.enterprise.ai.pipeline.PipelineContext;
import com.enterprise.ai.pipeline.PipelineException;
import com.enterprise.ai.pipeline.PipelineStep;
import com.enterprise.ai.pipeline.parser.DocumentParser;
import com.enterprise.ai.pipeline.parser.DocumentParserFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 步骤一：文件解析 — 将上传文件转换为原始文本。
 *
 * <p>通过 {@link DocumentParserFactory} 根据文件扩展名自动选择解析器，
 * 解析结果写入 {@link PipelineContext#setRawText(String)}。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FileParseStep implements PipelineStep {

    private final DocumentParserFactory parserFactory;

    @Override
    public void process(PipelineContext context) {
        if (context.getFile() == null || context.getFile().isEmpty()) {
            throw new PipelineException(getName(), context.getFileId(), "上传文件为空");
        }

        String fileName = context.getFileName();
        if (fileName == null || fileName.isEmpty()) {
            fileName = context.getFile().getOriginalFilename();
            context.setFileName(fileName);
        }

        if (!parserFactory.isSupported(fileName)) {
            throw new PipelineException(getName(), context.getFileId(),
                    "不支持的文件类型: " + fileName);
        }

        DocumentParser parser = parserFactory.getParser(fileName);
        String rawText = parser.parse(context.getFile());

        if (rawText == null || rawText.trim().isEmpty()) {
            throw new PipelineException(getName(), context.getFileId(),
                    "文件解析结果为空: " + fileName);
        }

        context.setRawText(rawText);
        log.debug("FileParseStep 完成: fileId={}, 文本长度={}", context.getFileId(), rawText.length());
    }

    @Override
    public String getName() {
        return "FILE_PARSE";
    }
}
