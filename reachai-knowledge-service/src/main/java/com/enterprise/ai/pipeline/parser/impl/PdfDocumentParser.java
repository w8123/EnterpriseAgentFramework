package com.enterprise.ai.pipeline.parser.impl;

import com.enterprise.ai.pipeline.parser.DocumentParser;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;

/**
 * PDF 文档解析器 — 基于 Apache PDFBox。
 * <p>使用 {@link PDFTextStripper} 按页顺序提取纯文本内容。</p>
 */
@Slf4j
@Component
public class PdfDocumentParser implements DocumentParser {

    @Override
    public String parse(MultipartFile file) {
        try (InputStream is = file.getInputStream();
             PDDocument document = PDDocument.load(is)) {

            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String content = stripper.getText(document).trim();

            log.debug("PDF 解析完成, 文件: {}, 页数: {}, 字符数: {}",
                    file.getOriginalFilename(), document.getNumberOfPages(), content.length());
            return content;

        } catch (Exception e) {
            throw new RuntimeException("PDF 文件解析失败: " + file.getOriginalFilename(), e);
        }
    }

    @Override
    public String[] supportedExtensions() {
        return new String[]{"pdf"};
    }
}
