package com.enterprise.ai.pipeline.parser;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文档解析器工厂 — 根据文件扩展名自动选择解析器。
 *
 * <p>Spring 容器启动时自动收集所有 {@link DocumentParser} 实现，
 * 新增格式只需实现接口并注册为 Bean，无需修改工厂代码。</p>
 */
@Slf4j
@Component
public class DocumentParserFactory {

    private final Map<String, DocumentParser> parserMap = new HashMap<>();

    public DocumentParserFactory(List<DocumentParser> parsers) {
        for (DocumentParser parser : parsers) {
            for (String ext : parser.supportedExtensions()) {
                parserMap.put(ext.toLowerCase(), parser);
                log.debug("注册文档解析器: {} → {}", ext, parser.getClass().getSimpleName());
            }
        }
    }

    /**
     * 根据文件名获取解析器
     *
     * @param fileName 文件名（如 "合同.docx"）
     * @return 对应的解析器
     * @throws IllegalArgumentException 不支持的文件类型
     */
    public DocumentParser getParser(String fileName) {
        String ext = extractExtension(fileName);
        DocumentParser parser = parserMap.get(ext);
        if (parser == null) {
            throw new IllegalArgumentException("不支持的文件类型: " + ext + " (文件: " + fileName + ")");
        }
        return parser;
    }

    public boolean isSupported(String fileName) {
        return parserMap.containsKey(extractExtension(fileName));
    }

    private String extractExtension(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            return "";
        }
        return fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();
    }
}
