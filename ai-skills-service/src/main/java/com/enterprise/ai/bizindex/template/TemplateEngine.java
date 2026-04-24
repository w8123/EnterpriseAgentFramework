package com.enterprise.ai.bizindex.template;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 业务索引文本模板引擎 —— 将模板中的占位符替换为实际业务字段值。
 *
 * <h3>模板语法</h3>
 * <ul>
 *   <li>{@code {fieldName}} — 替换为字段值，字段不存在或为空时替换为空字符串</li>
 *   <li>{@code {fieldName|默认文本}} — 字段为空时使用管道符后的默认文本</li>
 * </ul>
 *
 * <h3>示例</h3>
 * <pre>
 * 模板：物资名称：{name}，规格型号：{spec|未知}，用途：{useScene}
 * 字段：{name: "密封圈", spec: null, useScene: "管道密封"}
 * 输出：物资名称：密封圈，规格型号：未知，用途：管道密封
 * </pre>
 */
@Slf4j
@Component
public class TemplateEngine {

    /** 匹配 {fieldName} 或 {fieldName|defaultValue} */
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{(\\w+)(?:\\|([^}]*))?}");

    /**
     * 根据模板和字段值渲染最终索引文本
     *
     * @param template 文本模板，如 "物资名称：{name}，规格：{spec}"
     * @param fields   字段键值对
     * @return 渲染后的文本
     */
    public String render(String template, Map<String, String> fields) {
        if (template == null || template.isBlank()) {
            throw new IllegalArgumentException("模板不能为空");
        }
        if (fields == null || fields.isEmpty()) {
            throw new IllegalArgumentException("字段不能为空");
        }

        Matcher matcher = PLACEHOLDER_PATTERN.matcher(template);
        StringBuilder sb = new StringBuilder();

        while (matcher.find()) {
            String fieldName = matcher.group(1);
            String defaultValue = matcher.group(2);

            String value = fields.get(fieldName);
            if (value == null || value.isBlank()) {
                value = (defaultValue != null) ? defaultValue : "";
            }
            matcher.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(sb);

        return sb.toString().trim();
    }

    /**
     * 校验模板语法是否合法：至少包含一个占位符
     */
    public boolean validate(String template) {
        if (template == null || template.isBlank()) {
            return false;
        }
        return PLACEHOLDER_PATTERN.matcher(template).find();
    }
}
