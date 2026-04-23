package com.enterprise.ai.agent.semantic.context;

import java.util.List;

/**
 * 组装给 LLM 的上下文输入。根据目标层级填充对应字段。
 * <ul>
 *     <li>项目级：projectName / projectDescription / readmeExcerpt / moduleIndex</li>
 *     <li>模块级：moduleName / controllerSource + 关联 service/mapper 源码片段</li>
 *     <li>接口级：toolName / httpMethod / endpointPath / methodSource + 关联调用片段 + DTO 片段</li>
 * </ul>
 */
public record SemanticContext(
        String level,
        String projectName,
        String projectDescription,
        String readmeExcerpt,
        List<String> moduleIndex,
        String moduleName,
        List<SourceSnippet> controllerSources,
        List<SourceSnippet> serviceSnippets,
        List<SourceSnippet> mapperSnippets,
        List<SourceSnippet> dtoSnippets,
        String toolName,
        String toolEndpoint,
        String toolMethodSource
) {

    public static final String LEVEL_PROJECT = "project";
    public static final String LEVEL_MODULE = "module";
    public static final String LEVEL_TOOL = "tool";

    public record SourceSnippet(String qualifier, String content, boolean trimmed) {
    }
}
