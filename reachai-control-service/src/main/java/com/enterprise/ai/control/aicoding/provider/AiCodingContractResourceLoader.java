package com.enterprise.ai.control.aicoding.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class AiCodingContractResourceLoader {

    private static final String ROOT = "ai-coding/contracts/";

    private final ObjectMapper objectMapper;

    public JsonNode load(String fileName) {
        ClassPathResource resource = new ClassPathResource(ROOT + fileName);
        if (!resource.exists()) {
            throw new IllegalStateException(
                    "Missing AI Coding contract resource: " + fileName);
        }
        try (var input = resource.getInputStream()) {
            return objectMapper.readTree(input);
        } catch (IOException ex) {
            throw new IllegalStateException(
                    "Invalid AI Coding contract resource: " + fileName,
                    ex);
        }
    }
}
