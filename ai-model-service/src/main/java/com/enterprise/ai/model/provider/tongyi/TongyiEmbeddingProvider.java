package com.enterprise.ai.model.provider.tongyi;

import com.enterprise.ai.model.service.EmbeddingResponse;
import com.enterprise.ai.model.util.EmbeddingDebugLogs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * 通义 Embedding 独立实现（因 Spring AI DashScope Embedding 尚不完善，先保留 REST 直调方式）。
 */
@Slf4j
@Component
public class TongyiEmbeddingProvider {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${model.tongyi.api-key:${spring.ai.dashscope.api-key:}}")
    private String apiKey;

    @Value("${model.tongyi.embedding.model:text-embedding-v2}")
    private String defaultModel;

    @Value("${model.tongyi.embedding.endpoint:https://dashscope.aliyuncs.com/api/v1/services/embeddings/text-embedding/text-embedding}")
    private String endpoint;

    @Value("${model.tongyi.embedding.batch-size:25}")
    private int batchSize;

    @Value("${model.tongyi.embedding.dimension:1536}")
    private int dimension;

    public TongyiEmbeddingProvider(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    public EmbeddingResponse embed(List<String> texts, String model) {
        String useModel = (model != null && !model.isBlank()) ? model : defaultModel;
        int totalBatches = (int) Math.ceil((double) texts.size() / batchSize);
        log.info("[Tongyi Embedding] 开始向量化，model={}, 文本数量={}, batchSize={}, 批次数={}",
                useModel, texts.size(), batchSize, totalBatches);

        List<List<Float>> allEmbeddings = new ArrayList<>();
        long start = System.currentTimeMillis();

        for (int i = 0; i < texts.size(); i += batchSize) {
            List<String> batch = texts.subList(i, Math.min(i + batchSize, texts.size()));
            int batchIndex = i / batchSize + 1;
            log.debug("[Tongyi Embedding] 调用第 {}/{} 批次，本批文本数={}", batchIndex, totalBatches, batch.size());
            EmbeddingDebugLogs.logInputTexts(log, "[Tongyi Embedding] 本批 DashScope input.texts", batch);
            allEmbeddings.addAll(callApi(batch, useModel));
        }

        log.info("[Tongyi Embedding] 向量化完成 耗时={}ms, 返回向量数={}, 维度={}",
                System.currentTimeMillis() - start, allEmbeddings.size(), dimension);

        return EmbeddingResponse.builder()
                .model(useModel)
                .provider("tongyi")
                .dimension(dimension)
                .embeddings(allEmbeddings)
                .build();
    }

    private List<List<Float>> callApi(List<String> texts, String model) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + apiKey);

            Map<String, Object> input = Map.of("texts", texts);
            Map<String, Object> params = Map.of("text_type", "query");
            Map<String, Object> body = Map.of("model", model, "input", input, "parameters", params);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            long start = System.currentTimeMillis();
            ResponseEntity<String> response = restTemplate.exchange(endpoint, HttpMethod.POST, entity, String.class);
            log.debug("[Tongyi Embedding] DashScope API 响应 耗时={}ms, status={}",
                    System.currentTimeMillis() - start, response.getStatusCode());

            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode embeddings = root.path("output").path("embeddings");

            List<List<Float>> result = new ArrayList<>();
            for (JsonNode node : embeddings) {
                List<Float> vector = new ArrayList<>();
                for (JsonNode v : node.path("embedding")) {
                    vector.add(v.floatValue());
                }
                result.add(vector);
            }
            return result;
        } catch (Exception e) {
            log.error("[Tongyi Embedding] DashScope API 调用失败，endpoint={}", endpoint, e);
            throw new RuntimeException("Embedding API call failed", e);
        }
    }

    public int getDimension() {
        return dimension;
    }
}
