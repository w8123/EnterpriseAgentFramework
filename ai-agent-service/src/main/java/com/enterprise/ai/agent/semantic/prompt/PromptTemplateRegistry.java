package com.enterprise.ai.agent.semantic.prompt;

import com.enterprise.ai.agent.semantic.context.SemanticContext;
import com.enterprise.ai.agent.semantic.context.SemanticContext.SourceSnippet;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.FileCopyUtils;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 加载 {@code classpath:/prompts/semantic/*.prompt.md} 模板并按层级渲染。
 * 使用最简单的 {@code {{placeholder}}} 占位替换，避免引入额外模板引擎。
 */
@Component
public class PromptTemplateRegistry {

    private static final Logger log = LoggerFactory.getLogger(PromptTemplateRegistry.class);

    /** 模板版本号，随 prompt 内容变更递增，写入 semantic_doc.prompt_version。 */
    public static final String VERSION = "v1";

    private final Map<String, String> templates = new HashMap<>();

    @PostConstruct
    public void load() {
        templates.put(SemanticContext.LEVEL_PROJECT, loadResource("prompts/semantic/project.prompt.md"));
        templates.put(SemanticContext.LEVEL_MODULE, loadResource("prompts/semantic/module.prompt.md"));
        templates.put(SemanticContext.LEVEL_TOOL, loadResource("prompts/semantic/tool.prompt.md"));
    }

    public String render(SemanticContext context) {
        String template = templates.get(context.level());
        if (!StringUtils.hasText(template)) {
            throw new IllegalStateException("未找到 " + context.level() + " 层级的 prompt 模板");
        }
        String rendered = template;
        rendered = replace(rendered, "projectName", safe(context.projectName()));
        rendered = replace(rendered, "projectDescription", safe(context.projectDescription()));
        rendered = replace(rendered, "readmeExcerpt", safe(context.readmeExcerpt()));
        rendered = replace(rendered, "moduleIndex", formatModuleIndex(context.moduleIndex()));
        rendered = replace(rendered, "moduleName", safe(context.moduleName()));
        rendered = replace(rendered, "controllerSources", formatSnippets(context.controllerSources()));
        rendered = replace(rendered, "serviceSnippets", formatSnippets(context.serviceSnippets()));
        rendered = replace(rendered, "mapperSnippets", formatSnippets(context.mapperSnippets()));
        rendered = replace(rendered, "dtoSnippets", formatSnippets(context.dtoSnippets()));
        rendered = replace(rendered, "toolName", safe(context.toolName()));
        rendered = replace(rendered, "toolEndpoint", safe(context.toolEndpoint()));
        rendered = replace(rendered, "toolMethodSource", safe(context.toolMethodSource()));
        return rendered;
    }

    private String loadResource(String location) {
        try (InputStream in = new ClassPathResource(location).getInputStream()) {
            return new String(FileCopyUtils.copyToByteArray(in), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            log.error("[PromptTemplateRegistry] 加载模板失败: {}", location, ex);
            return "";
        }
    }

    private String replace(String template, String key, String value) {
        return template.replace("{{" + key + "}}", value == null ? "" : value);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String formatModuleIndex(List<String> modules) {
        if (modules == null || modules.isEmpty()) {
            return "(未识别到模块)";
        }
        StringBuilder sb = new StringBuilder();
        for (String m : modules) {
            sb.append("- ").append(m).append('\n');
        }
        return sb.toString();
    }

    private String formatSnippets(List<SourceSnippet> snippets) {
        if (snippets == null || snippets.isEmpty()) {
            return "(无)";
        }
        StringBuilder sb = new StringBuilder();
        for (SourceSnippet s : snippets) {
            sb.append("// ===== ").append(s.qualifier());
            if (s.trimmed()) {
                sb.append(" (truncated)");
            }
            sb.append(" =====\n");
            sb.append(s.content() == null ? "" : s.content()).append("\n\n");
        }
        return sb.toString();
    }
}
