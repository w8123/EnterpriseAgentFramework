package com.enterprise.ai.rag.impl;

import com.enterprise.ai.rag.LlmService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * 通义千问 LLM 实现
 */
@Slf4j
@Service
public class TongyiLlmService implements LlmService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${llm.tongyi.api-key}")
    private String apiKey;

    @Value("${llm.tongyi.model}")
    private String model;

    @Value("${llm.tongyi.endpoint}")
    private String endpoint;

    public TongyiLlmService(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public String chat(String prompt) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + apiKey);

            Map<String, Object> input = new HashMap<>();
            input.put("prompt", prompt);

            Map<String, Object> body = new HashMap<>();
            body.put("model", model);
            body.put("input", input);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.exchange(endpoint, HttpMethod.POST, entity, String.class);

            JsonNode root = objectMapper.readTree(response.getBody());
            return root.path("output").path("text").asText();
        } catch (Exception e) {
            log.error("通义 LLM 调用失败", e);
            throw new RuntimeException("LLM API call failed", e);
        }
    }

    @Override
    public String getModelName() {
        return model;
    }
}
