package com.enterprise.ai.pipeline.parser.impl;

import com.enterprise.ai.pipeline.parser.DocumentParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

/**
 * 纯文本文件解析器（txt / md / csv 等）
 */
@Slf4j
@Component
public class PlainTextDocumentParser implements DocumentParser {

    @Override
    public String parse(MultipartFile file) {
        try (InputStream is = file.getInputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String content = reader.lines().collect(Collectors.joining("\n"));
            log.debug("文本解析完成, 文件: {}, 字符数: {}", file.getOriginalFilename(), content.length());
            return content;
        } catch (Exception e) {
            throw new RuntimeException("文本文件解析失败: " + file.getOriginalFilename(), e);
        }
    }

    @Override
    public String[] supportedExtensions() {
        return new String[]{"txt", "md", "csv"};
    }
}
