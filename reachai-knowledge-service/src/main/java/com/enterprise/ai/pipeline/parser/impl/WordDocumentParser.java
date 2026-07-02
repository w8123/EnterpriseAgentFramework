package com.enterprise.ai.pipeline.parser.impl;

import com.enterprise.ai.pipeline.parser.DocumentParser;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.List;

/**
 * Word 文档解析器 — 基于 Apache POI。
 * <ul>
 *   <li>.docx（Office Open XML）使用 {@link XWPFDocument}</li>
 *   <li>.doc（OLE2 二进制格式）使用 {@link HWPFDocument}</li>
 * </ul>
 */
@Slf4j
@Component
public class WordDocumentParser implements DocumentParser {

    @Override
    public String parse(MultipartFile file) {
        String fileName = file.getOriginalFilename();
        if (fileName != null && fileName.toLowerCase().endsWith(".doc") && !fileName.toLowerCase().endsWith(".docx")) {
            return parseDoc(file);
        }
        return parseDocx(file);
    }

    /**
     * 解析 .docx 格式（基于 XML 的 Office Open XML）
     */
    private String parseDocx(MultipartFile file) {
        try (InputStream is = file.getInputStream();
             XWPFDocument document = new XWPFDocument(is)) {

            List<XWPFParagraph> paragraphs = document.getParagraphs();
            StringBuilder sb = new StringBuilder();

            for (XWPFParagraph paragraph : paragraphs) {
                String text = paragraph.getText();
                if (text != null && !text.isBlank()) {
                    sb.append(text).append("\n");
                }
            }

            String content = sb.toString().trim();
            log.debug("Word(.docx) 解析完成, 文件: {}, 段落数: {}, 字符数: {}",
                    file.getOriginalFilename(), paragraphs.size(), content.length());
            return content;

        } catch (Exception e) {
            throw new RuntimeException("Word(.docx) 文件解析失败: " + file.getOriginalFilename(), e);
        }
    }

    /**
     * 解析 .doc 格式（OLE2 二进制）
     */
    private String parseDoc(MultipartFile file) {
        try (InputStream is = file.getInputStream();
             HWPFDocument document = new HWPFDocument(is);
             WordExtractor extractor = new WordExtractor(document)) {

            String content = extractor.getText().trim();
            log.debug("Word(.doc) 解析完成, 文件: {}, 字符数: {}",
                    file.getOriginalFilename(), content.length());
            return content;

        } catch (Exception e) {
            throw new RuntimeException("Word(.doc) 文件解析失败: " + file.getOriginalFilename(), e);
        }
    }

    @Override
    public String[] supportedExtensions() {
        return new String[]{"doc", "docx"};
    }
}
