package com.enterprise.ai.pipeline.step;

import com.enterprise.ai.pipeline.PipelineContext;
import com.enterprise.ai.pipeline.PipelineException;
import com.enterprise.ai.pipeline.PipelineStep;
import com.enterprise.ai.pipeline.document.DocumentParseException;
import com.enterprise.ai.pipeline.document.DocumentParseRequest;
import com.enterprise.ai.pipeline.document.DocumentParseResult;
import com.enterprise.ai.pipeline.document.DocumentParseRouter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 步骤一：文件解析 — 将上传文件转换为原始文本。
 *
 * <p>通过 {@link DocumentParseRouter} 的显式路由选择 Java Fast 或 Docling。
 * 路由失败即终止，不会回退到旧解析器。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FileParseStep implements PipelineStep {

    private final DocumentParseRouter parseRouter;

    @Override
    public void process(PipelineContext context) {
        // An asynchronous job already parsed and persisted the structured
        // result.  Continue from that immutable artifact instead of calling
        // Docling a second time.
        if (context.getParsedDocument() != null) {
            if (context.getRawText() == null || context.getRawText().isBlank()) {
                context.setRawText(context.getParsedDocument().getNormalizedText());
            }
            return;
        }
        if (context.getFile() == null || context.getFile().isEmpty()) {
            throw new PipelineException(getName(), context.getFileId(), "上传文件为空");
        }

        String fileName = context.getFileName();
        if (fileName == null || fileName.isEmpty()) {
            fileName = context.getFile().getOriginalFilename();
            context.setFileName(fileName);
        }

        try {
            DocumentParseRequest request = DocumentParseRequest.fromMultipartFile(context.getFile());
            request.getOptions().put("importId", context.getFileId());
            DocumentParseResult parsedDocument = parseRouter.parse(request);
            context.setParsedDocument(parsedDocument);
            context.setRawText(parsedDocument.getNormalizedText());
            log.debug("FileParseStep 完成: fileId={}, provider={}, format={}, 文本长度={}",
                    context.getFileId(), parsedDocument.getProviderType(), parsedDocument.getFormat(),
                    parsedDocument.getNormalizedText().length());
        } catch (DocumentParseException e) {
            throw new PipelineException(getName(), context.getFileId(),
                    e.getErrorCode().name() + ": " + e.getMessage(), e);
        }
    }

    @Override
    public String getName() {
        return "FILE_PARSE";
    }
}
