package com.enterprise.ai.embedding.impl;

import com.enterprise.ai.embedding.EmbeddingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * 通义千问 Embedding 实现
 */
@Slf4j
@Service
public class TongyiEmbeddingService implements EmbeddingService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${embedding.tongyi.api-key}")
    private String apiKey;

    @Value("${embedding.tongyi.model}")
    private String model;

    @Value("${embedding.tongyi.endpoint}")
    private String endpoint;

    @Value("${embedding.tongyi.batch-size:25}")
    private int batchSize;

    @Value("${milvus.dimension:1536}")
    private int dimension;

    public TongyiEmbeddingService(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<Float> embed(String text) {
        List<List<Float>> results = embedBatch(List.of(text));
        return results.get(0);
    }

    @Override
    public List<List<Float>> embedBatch(List<String> texts) {
        List<List<Float>> allEmbeddings = new ArrayList<>();

        for (int i = 0; i < texts.size(); i += batchSize) {
            List<String> batch = texts.subList(i, Math.min(i + batchSize, texts.size()));
            List<List<Float>> batchResult = callEmbeddingApi(batch);
            allEmbeddings.addAll(batchResult);
        }

        return allEmbeddings;
    }

    @Override
    public String getModelName() {
        return model;
    }

    @Override
    public int getDimension() {
        return dimension;
    }

    private List<List<Float>> callEmbeddingApi(List<String> texts) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + apiKey);

            Map<String, Object> input = new HashMap<>();
            input.put("texts", texts);

            Map<String, Object> params = new HashMap<>();
            params.put("text_type", "query");

            Map<String, Object> body = new HashMap<>();
            body.put("model", model);
            body.put("input", input);
            body.put("parameters", params);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.exchange(endpoint, HttpMethod.POST, entity, String.class);

            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode embeddings = root.path("output").path("embeddings");

            List<List<Float>> result = new ArrayList<>();
            for (JsonNode embeddingNode : embeddings) {
                JsonNode vectorNode = embeddingNode.path("embedding");
                List<Float> vector = new ArrayList<>();
                for (JsonNode v : vectorNode) {
                    vector.add(v.floatValue());
                }
                result.add(vector);
            }
            return result;
        } catch (Exception e) {
            log.error("通义 Embedding API 调用失败", e);
            throw new RuntimeException("Embedding API call failed", e);
        }
    }
}
