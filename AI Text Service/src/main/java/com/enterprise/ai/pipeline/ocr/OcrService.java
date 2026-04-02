package com.enterprise.ai.pipeline.ocr;

/**
 * OCR 识别服务接口 — 将图片/扫描件中的文字提取为纯文本。
 *
 * <p>扩展点：可接入百度OCR、腾讯OCR、PaddleOCR 等实现。</p>
 */
public interface OcrService {

    /**
     * 对文本进行 OCR 增强处理。
     * <p>对于已经是纯文本的内容，可直接透传。
     * 对于扫描件/图片提取的文本，进行 OCR 识别后合并。</p>
     *
     * @param rawText 原始文本（可能包含乱码或不完整内容）
     * @param context 额外参数（如语言、图片路径等）
     * @return OCR 处理后的文本
     */
    String process(String rawText, java.util.Map<String, Object> context);

    /**
     * 是否需要执行 OCR（用于步骤判断是否跳过）
     */
    boolean isRequired(java.util.Map<String, Object> context);
}
