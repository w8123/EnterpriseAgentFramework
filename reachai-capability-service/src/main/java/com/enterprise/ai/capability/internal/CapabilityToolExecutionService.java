package com.enterprise.ai.capability.internal;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectToolEntity;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionEntity;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CapabilityToolExecutionService {

    private final ToolDefinitionMapper toolDefinitionMapper;
    private final CapabilityHttpToolInvoker invoker;

    public Map<String, Object> execute(String qualifiedName, Map<String, Object> request) {
        ToolDefinitionEntity tool = findTool(qualifiedName);
        if (!Boolean.TRUE.equals(tool.getEnabled())) {
            throw new IllegalStateException("Tool definition is disabled: " + qualifiedName);
        }
        Map<String, Object> input = mapValue(request == null ? null : request.get("input"));
        input = input == null ? Map.of() : input;
        String method = StringUtils.hasText(tool.getHttpMethod()) ? tool.getHttpMethod().trim().toUpperCase() : "POST";
        String url = buildUrl(tool);
        if (!StringUtils.hasText(url)) {
            throw new IllegalStateException("Tool definition endpoint is missing: " + qualifiedName);
        }
        if ("GET".equals(method) && !input.isEmpty()) {
            url = appendQuery(url, input);
        }
        CapabilityHttpToolInvocation invocation = new CapabilityHttpToolInvocation(
                method,
                url,
                "GET".equals(method) ? Map.of() : input,
                Map.of(
                        "qualifiedName", tool.getQualifiedName(),
                        "toolName", tool.getName(),
                        "requestBodyType", nullToEmpty(tool.getRequestBodyType()),
                        "responseType", nullToEmpty(tool.getResponseType())));
        Map<String, Object> invoked = invoker.invoke(invocation);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("qualifiedName", tool.getQualifiedName());
        response.put("toolName", tool.getName());
        response.put("data", invoked == null ? null : invoked.get("body"));
        response.put("metadata", invoked == null ? Map.of() : invoked);
        return response;
    }

    public Map<String, Object> execute(ScanProjectToolEntity tool, Map<String, Object> request) {
        if (tool == null) {
            throw new IllegalArgumentException("Scan project tool not found");
        }
        if (!Boolean.TRUE.equals(tool.getEnabled())) {
            throw new IllegalStateException("Scan project tool is disabled: " + tool.getId());
        }
        Map<String, Object> input = mapValue(request == null ? null : request.get("input"));
        input = input == null ? Map.of() : input;
        String method = StringUtils.hasText(tool.getHttpMethod()) ? tool.getHttpMethod().trim().toUpperCase() : "POST";
        String url = buildUrl(tool);
        if (!StringUtils.hasText(url)) {
            throw new IllegalStateException("Scan project tool endpoint is missing: " + tool.getId());
        }
        if ("GET".equals(method) && !input.isEmpty()) {
            url = appendQuery(url, input);
        }
        CapabilityHttpToolInvocation invocation = new CapabilityHttpToolInvocation(
                method,
                url,
                "GET".equals(method) ? Map.of() : input,
                Map.of(
                        "scanToolId", tool.getId(),
                        "toolName", tool.getName(),
                        "requestBodyType", nullToEmpty(tool.getRequestBodyType()),
                        "responseType", nullToEmpty(tool.getResponseType())));
        Map<String, Object> invoked = invoker.invoke(invocation);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("scanToolId", tool.getId());
        response.put("toolName", tool.getName());
        response.put("data", invoked == null ? null : invoked.get("body"));
        response.put("metadata", invoked == null ? Map.of() : invoked);
        return response;
    }

    private ToolDefinitionEntity findTool(String qualifiedName) {
        if (!StringUtils.hasText(qualifiedName)) {
            throw new IllegalArgumentException("Tool definition not found: " + qualifiedName);
        }
        String key = qualifiedName.trim();
        ToolDefinitionEntity entity = toolDefinitionMapper.selectOne(Wrappers.<ToolDefinitionEntity>lambdaQuery()
                .eq(ToolDefinitionEntity::getQualifiedName, key)
                .last("limit 1"));
        if (entity == null) {
            entity = toolDefinitionMapper.selectOne(Wrappers.<ToolDefinitionEntity>lambdaQuery()
                    .eq(ToolDefinitionEntity::getName, key)
                    .last("limit 1"));
        }
        if (entity == null) {
            throw new IllegalArgumentException("Tool definition not found: " + key);
        }
        return entity;
    }

    private String buildUrl(ToolDefinitionEntity tool) {
        StringBuilder url = new StringBuilder();
        appendUrlPart(url, tool.getBaseUrl());
        appendUrlPart(url, tool.getContextPath());
        appendUrlPart(url, tool.getEndpointPath());
        return url.toString();
    }

    private String buildUrl(ScanProjectToolEntity tool) {
        StringBuilder url = new StringBuilder();
        appendUrlPart(url, tool.getBaseUrl());
        appendUrlPart(url, tool.getContextPath());
        appendUrlPart(url, tool.getEndpointPath());
        return url.toString();
    }

    private void appendUrlPart(StringBuilder url, String part) {
        if (!StringUtils.hasText(part)) {
            return;
        }
        String value = part.trim();
        if (url.isEmpty()) {
            url.append(trimTrailingSlash(value));
            return;
        }
        url.append('/').append(trimSlashes(value));
    }

    private String trimTrailingSlash(String value) {
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private String trimSlashes(String value) {
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private String appendQuery(String url, Map<String, Object> input) {
        StringBuilder builder = new StringBuilder(url);
        builder.append(url.contains("?") ? '&' : '?');
        boolean first = true;
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            if (!first) {
                builder.append('&');
            }
            first = false;
            builder.append(encode(entry.getKey()))
                    .append('=')
                    .append(encode(String.valueOf(entry.getValue())));
        }
        return builder.toString();
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
