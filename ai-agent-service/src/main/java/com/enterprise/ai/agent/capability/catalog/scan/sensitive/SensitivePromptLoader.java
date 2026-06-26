package com.enterprise.ai.agent.capability.catalog.scan.sensitive;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.FileCopyUtils;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

@Component
public class SensitivePromptLoader {

    private static final Logger log = LoggerFactory.getLogger(SensitivePromptLoader.class);

    private String template = "";

    @PostConstruct
    public void load() {
        try (InputStream in = new ClassPathResource("prompts/sensitive/scan_tool_sensitive.prompt.md").getInputStream()) {
            template = new String(FileCopyUtils.copyToByteArray(in), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            log.error("[SensitivePromptLoader] 加载模板失败", ex);
            template = "";
        }
    }

    public String renderUserPrompt(String toolSpecJson) {
        if (template.isBlank()) {
            throw new IllegalStateException("敏感扫描 prompt 模板未加载");
        }
        String allowed = SensitiveDataType.allCodesSorted().stream()
                .map(c -> "- " + c)
                .collect(Collectors.joining("\n"));
        return template
                .replace("{{allowedTypes}}", allowed)
                .replace("{{toolSpec}}", toolSpecJson == null ? "" : toolSpecJson);
    }
}
