package com.enterprise.ai.runtime.client.model;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@FeignClient(
        name = "reachai-model-catalog-runtime",
        url = "${services.model-service.url:http://localhost:18601}"
)
public interface RuntimeModelCatalogClient {

    @GetMapping("/internal/model/instances")
    ResponseEntity<List<Map<String, Object>>> listInternal(
            @RequestParam(value = "modelType", required = false) String modelType,
            @RequestParam(value = "status", required = false) String status);

    default List<Map<String, Object>> listActiveLlms() {
        ResponseEntity<List<Map<String, Object>>> response = listInternal("LLM", "ACTIVE");
        List<Map<String, Object>> body = response == null ? null : response.getBody();
        if (body == null) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> item : body) {
            if (item == null) {
                continue;
            }
            Map<String, Object> normalized = new LinkedHashMap<>();
            normalized.put("id", stringValue(item.get("id")));
            normalized.put("keySlug", stringValue(item.get("keySlug")));
            normalized.put("displayName", firstText(stringValue(item.get("displayName")), stringValue(item.get("name"))));
            normalized.put("provider", stringValue(item.get("provider")));
            normalized.put("modelName", stringValue(item.get("modelName")));
            normalized.put("modelType", stringValue(item.get("modelType")));
            normalized.put("status", stringValue(item.get("status")));
            result.add(normalized);
        }
        return result;
    }

    default boolean isActiveLlm(String modelInstanceId) {
        if (!StringUtils.hasText(modelInstanceId)) {
            return false;
        }
        return listActiveLlms().stream()
                .anyMatch(item -> modelInstanceId.trim().equals(stringValue(item.get("id"))));
    }

    default String firstActiveLlmId() {
        return listActiveLlms().stream()
                .map(item -> stringValue(item.get("id")))
                .filter(StringUtils::hasText)
                .findFirst()
                .orElse(null);
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String firstText(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }
}
