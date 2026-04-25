package com.enterprise.ai.agent.scan;

import com.enterprise.ai.agent.tools.dynamic.DynamicHttpAiTool;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * 将 {@link ScanProjectEntity} 上的鉴权配置转为 HTTP 调用附加头与查询参数。
 */
public final class ScanProjectAuthSupport {

    private ScanProjectAuthSupport() {
    }

    public static DynamicHttpAiTool.HttpInvocationExtras invocationExtras(ScanProjectEntity project) {
        if (project == null || !StringUtils.hasText(project.getAuthType())
                || "none".equalsIgnoreCase(project.getAuthType().trim())) {
            return DynamicHttpAiTool.HttpInvocationExtras.EMPTY;
        }
        if (!"api_key".equalsIgnoreCase(project.getAuthType().trim())) {
            return DynamicHttpAiTool.HttpInvocationExtras.EMPTY;
        }
        String name = project.getAuthApiKeyName() == null ? "" : project.getAuthApiKeyName().trim();
        String value = project.getAuthApiKeyValue() == null ? "" : project.getAuthApiKeyValue();
        if (!StringUtils.hasText(name)) {
            return DynamicHttpAiTool.HttpInvocationExtras.EMPTY;
        }
        String in = project.getAuthApiKeyIn() == null ? "" : project.getAuthApiKeyIn().trim();
        if ("header".equalsIgnoreCase(in)) {
            return new DynamicHttpAiTool.HttpInvocationExtras(Map.of(name, value), Map.of());
        }
        if ("query".equalsIgnoreCase(in)) {
            return new DynamicHttpAiTool.HttpInvocationExtras(Map.of(), Map.of(name, value));
        }
        return DynamicHttpAiTool.HttpInvocationExtras.EMPTY;
    }
}
