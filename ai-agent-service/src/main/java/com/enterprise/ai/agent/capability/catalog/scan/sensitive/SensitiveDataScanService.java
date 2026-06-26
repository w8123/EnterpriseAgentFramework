package com.enterprise.ai.agent.capability.catalog.scan.sensitive;

import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectToolEntity;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectToolService;
import com.enterprise.ai.agent.capability.catalog.semantic.llm.SemanticLlmClient;
import com.enterprise.ai.agent.capability.catalog.semantic.llm.SemanticLlmClient.SemanticGenerationResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 单条扫描接口的敏感数据 LLM 分析与落库。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SensitiveDataScanService {

    private final ObjectMapper objectMapper;
    private final SensitivePromptLoader promptLoader;
    private final SemanticLlmClient llmClient;
    private final ScanProjectToolService scanProjectToolService;

    public int scanAndPersist(ScanProjectToolEntity tool, String modelInstanceId) {
        String toolSpec = buildToolSpecJson(tool);
        String userPrompt = promptLoader.renderUserPrompt(toolSpec);
        SemanticGenerationResult result = llmClient.generateSensitiveScan(userPrompt, modelInstanceId);
        parseAndStore(result.content(), result.modelName(), tool.getId());
        return result.tokenUsage();
    }

    public void persistFailure(long scanToolId, String reason, String modelName) {
        try {
            SensitiveDataStored stored = new SensitiveDataStored();
            stored.setTypes(List.of());
            stored.setSummary("扫描失败: " + (reason == null ? "未知错误" : reason));
            stored.setScannedAt(Instant.now().toString());
            stored.setModelName(modelName);
            String json = objectMapper.writeValueAsString(stored);
            scanProjectToolService.updateSensitiveDataJson(scanToolId, json);
        } catch (Exception ex) {
            log.warn("[SensitiveDataScanService] 写入失败占位失败 scanToolId={}", scanToolId, ex);
        }
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
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(spec);
        } catch (Exception ex) {
            throw new IllegalStateException("无法序列化接口规格", ex);
        }
    }

    private void parseAndStore(String llmContent, String modelName, long scanToolId) {
        try {
            String stripped = stripJsonFences(llmContent);
            JsonNode root = objectMapper.readTree(stripped);
            List<String> raw = new ArrayList<>();
            JsonNode typesNode = root.get("types");
            if (typesNode != null && typesNode.isArray()) {
                for (JsonNode n : typesNode) {
                    if (n != null && n.isTextual()) {
                        raw.add(n.asText());
                    }
                }
            }
            Set<String> normalized = SensitiveDataType.normalizeTypes(raw);
            String summary = root.path("summary").asText("");
            if (summary.isBlank()) {
                summary = normalized.isEmpty() ? "未发现明确敏感字段特征" : "已根据参数与路径识别敏感类型";
            }
            SensitiveDataStored stored = new SensitiveDataStored();
            stored.setTypes(new ArrayList<>(normalized));
            stored.setSummary(summary);
            stored.setScannedAt(Instant.now().toString());
            stored.setModelName(modelName);
            String json = objectMapper.writeValueAsString(stored);
            scanProjectToolService.updateSensitiveDataJson(scanToolId, json);
        } catch (Exception ex) {
            log.warn("[SensitiveDataScanService] 解析 LLM 输出失败 scanToolId={}", scanToolId, ex);
            persistFailure(scanToolId, "模型输出解析失败: " + ex.getMessage(), modelName);
        }
    }

    static String stripJsonFences(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        if (s.startsWith("```")) {
            int firstNl = s.indexOf('\n');
            int lastFence = s.lastIndexOf("```");
            if (firstNl > 0 && lastFence > firstNl) {
                s = s.substring(firstNl + 1, lastFence).trim();
            }
        }
        return s;
    }
}
