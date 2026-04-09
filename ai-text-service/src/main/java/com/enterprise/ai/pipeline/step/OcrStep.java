package com.enterprise.ai.pipeline.step;

import com.enterprise.ai.pipeline.PipelineContext;
import com.enterprise.ai.pipeline.PipelineStep;
import com.enterprise.ai.pipeline.ocr.OcrService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 步骤二（可选）：OCR 识别 — 对扫描件/图片文本进行 OCR 增强。
 *
 * <p>当 {@link OcrService#isRequired(java.util.Map)} 返回 false 时自动跳过，
 * 直接将 rawText 透传为 ocrText。</p>
 *
 * <p>未来接入真实 OCR 引擎后，此步骤将自动生效。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OcrStep implements PipelineStep {

    private final OcrService ocrService;

    @Override
    public void process(PipelineContext context) {
        String rawText = context.getRawText();

        if (!ocrService.isRequired(context.getExtraParams())) {
            log.debug("OcrStep 跳过: OCR 非必需, fileId={}", context.getFileId());
            context.setOcrText(rawText);
            return;
        }

        String ocrText = ocrService.process(rawText, context.getExtraParams());
        context.setOcrText(ocrText);
        log.debug("OcrStep 完成: fileId={}, OCR后文本长度={}", context.getFileId(),
                ocrText != null ? ocrText.length() : 0);
    }

    @Override
    public String getName() {
        return "OCR";
    }
}
