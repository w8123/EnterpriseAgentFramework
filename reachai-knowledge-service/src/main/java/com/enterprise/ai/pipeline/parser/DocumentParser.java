package com.enterprise.ai.pipeline.parser;

import org.springframework.web.multipart.MultipartFile;

/**
 * 文档解析器接口 — 将上传文件转换为纯文本。
 *
 * <p>扩展方式：针对不同文件格式（Word、PDF、Excel、Markdown 等）
 * 实现此接口并注册到 {@link DocumentParserFactory}。</p>
 */
public interface DocumentParser {

    /**
     * 解析文件并提取纯文本内容
     *
     * @param file 上传文件
     * @return 提取的文本
     */
    String parse(MultipartFile file);

    /**
     * 当前解析器支持的文件类型后缀（不含点号，如 "pdf", "docx"）
     */
    String[] supportedExtensions();
}
