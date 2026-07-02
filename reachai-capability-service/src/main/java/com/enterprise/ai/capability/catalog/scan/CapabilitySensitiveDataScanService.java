package com.enterprise.ai.capability.catalog.scan;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectToolEntity;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectToolMapper;
import com.enterprise.ai.common.dto.ApiResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CapabilitySensitiveDataScanService {

    private static final String SYSTEM_PROMPT = """
            You are an enterprise API data-security analyst. Return only one valid JSON object with keys types and summary.
            Do not return Markdown, code fences, comments, or any text outside JSON.""";

    private final ObjectMapper objectMapper;
    private final CapabilityModelClient modelClient;
    private final ScanProjectToolMapper scanProjectToolMapper;

    public int scanAndPersist(ScanProjectToolEntity tool, String modelInstanceId) {
        String modelToUse = requireModelInstanceId(modelInstanceId);
        String userPrompt = renderUserPrompt(buildToolSpecJson(tool));
        ApiResult<CapabilityModelClient.ChatResponse> result = modelClient.chat(new CapabilityModelClient.ChatRequest(
                modelToUse,
                List.of(
                        new CapabilityModelClient.ChatMessage("system", SYSTEM_PROMPT),
                        new CapabilityModelClient.ChatMessage("user", userPrompt)),
                null,
                null,
                null));
        CapabilityModelClient.ChatResponse data = requireModelResponse(result);
        parseAndStore(data.content(), data.model() == null ? modelToUse : data.model(), tool.getId());
        return data.usage() == null ? 0 : data.usage().totalTokens();
    }

    public void persistFailure(long scanToolId, String reason, String modelName) {
        try {
            CapabilitySensitiveDataStored stored = new CapabilitySensitiveDataStored();
            stored.setTypes(List.of());
            stored.setSummary("扫描失败: " + (reason == null || reason.isBlank() ? "未知错误" : reason));
            stored.setScannedAt(Instant.now().toString());
            stored.setModelName(modelName);
            updateSensitiveDataJson(scanToolId, objectMapper.writeValueAsString(stored));
        } catch (Exception ignored) {
            // Nothing else can be done without hiding the original task failure.
        }
    }

    private CapabilityModelClient.ChatResponse requireModelResponse(ApiResult<CapabilityModelClient.ChatResponse> result) {
        if (result == null) {
            throw new IllegalStateException("Model service returned empty response");
        }
        if (result.getCode() != 200) {
            throw new IllegalStateException(result.getMessage() == null ? "Model service failed" : result.getMessage());
        }
        if (result.getData() == null || result.getData().content() == null) {
            throw new IllegalStateException("Model service returned empty content");
        }
        return result.getData();
    }

    private String buildToolSpecJson(ScanProjectToolEntity tool) {
        try {
            Map<String, Object> spec = new LinkedHashMap<>();
            spec.put("name", tool.getName());
            spec.put("description", tool.getDescription());
            spec.put("httpMethod", tool.getHttpMethod());
            spec.put("baseUrl", tool.getBaseUrl());
            spec.put("contextPath", tool.getContextPath());
            spec.put("endpointPath", tool.getEndpointPath());
            spec.put("requestBodyType", tool.getRequestBodyType());
            spec.put("responseType", tool.getResponseType());
            spec.put("sourceLocation", tool.getSourceLocation());
            spec.put("parametersJson", tool.getParametersJson());
            spec.put("capabilityMetadataJson", tool.getCapabilityMetadataJson());
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(spec);
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot serialize scan tool spec", ex);
        }
    }

    private String renderUserPrompt(String toolSpecJson) {
        String allowedTypes = CapabilitySensitiveDataType.allCodesSorted().stream()
                .map(code -> "- " + code)
                .collect(Collectors.joining("\n"));
        return """
                Analyze the following API endpoint and identify whether request/response fields may carry sensitive data.

                Allowed sensitive type codes:
                %s

                API endpoint spec:
                %s

                Return JSON shape:
                {"types":["PHONE"],"summary":"short Chinese summary"}
                """.formatted(allowedTypes, toolSpecJson == null ? "" : toolSpecJson);
    }

    private void parseAndStore(String rawContent, String modelName, long scanToolId) {
        try {
            JsonNode root = objectMapper.readTree(stripJsonFences(rawContent));
            List<String> rawTypes = new ArrayList<>();
            JsonNode types = root.get("types");
            if (types != null && types.isArray()) {
                for (JsonNode type : types) {
                    if (type != null && type.isTextual()) {
                        rawTypes.add(type.asText());
                    }
                }
            }
            Set<String> normalized = CapabilitySensitiveDataType.normalizeTypes(rawTypes);
            String summary = root.path("summary").asText("");
            if (summary.isBlank()) {
                summary = normalized.isEmpty() ? "未发现明确敏感字段特征" : "已根据参数与路径识别敏感类型";
            }
            CapabilitySensitiveDataStored stored = new CapabilitySensitiveDataStored();
            stored.setTypes(new ArrayList<>(normalized));
            stored.setSummary(summary);
            stored.setScannedAt(Instant.now().toString());
            stored.setModelName(modelName);
            updateSensitiveDataJson(scanToolId, objectMapper.writeValueAsString(stored));
        } catch (Exception ex) {
            persistFailure(scanToolId, "模型输出解析失败: " + ex.getMessage(), modelName);
        }
    }

    private void updateSensitiveDataJson(long scanToolId, String json) {
        scanProjectToolMapper.update(null, Wrappers.<ScanProjectToolEntity>update()
                .set("sensitive_data_json", json)
                .eq("id", scanToolId));
    }

    private String requireModelInstanceId(String modelInstanceId) {
        if (modelInstanceId == null || modelInstanceId.isBlank()) {
            throw new IllegalStateException("modelInstanceId is required for sensitive data scan");
        }
        return modelInstanceId.trim();
    }

    static String stripJsonFences(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.trim();
        if (!text.startsWith("```")) {
            return text;
        }
        int firstNewline = text.indexOf('\n');
        int lastFence = text.lastIndexOf("```");
        if (firstNewline > 0 && lastFence > firstNewline) {
            return text.substring(firstNewline + 1, lastFence).trim();
        }
        return text;
    }
}
