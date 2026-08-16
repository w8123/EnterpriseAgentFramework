package com.enterprise.ai.model.internal;

import com.enterprise.ai.model.instance.ModelInstanceResponse;
import com.enterprise.ai.model.instance.ModelInstanceService;
import com.enterprise.ai.model.instance.ModelInstanceStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/internal/model")
@RequiredArgsConstructor
public class ModelInstanceInternalController {

    private final ModelInstanceService modelInstanceService;

    @GetMapping("/instances")
    public ResponseEntity<List<Map<String, Object>>> list(
            @RequestParam(value = "modelType", required = false) String modelType,
            @RequestParam(value = "status", required = false) String status) {
        String normalizedType = StringUtils.hasText(modelType) ? modelType.trim().toUpperCase(Locale.ROOT) : null;
        String normalizedStatus = StringUtils.hasText(status) ? status.trim().toUpperCase(Locale.ROOT) : null;
        boolean includeArchived = ModelInstanceStatus.ARCHIVED.name().equals(normalizedStatus);
        List<Map<String, Object>> items = modelInstanceService.list(null, normalizedType, null, null, includeArchived)
                .stream()
                .filter(item -> normalizedType == null || normalizedType.equalsIgnoreCase(item.getModelType()))
                .filter(item -> normalizedStatus == null || normalizedStatus.equalsIgnoreCase(item.getStatus()))
                .map(this::toCatalogItem)
                .toList();
        return ResponseEntity.ok(items);
    }

    @GetMapping("/instances/{id}")
    public ResponseEntity<Map<String, Object>> get(
            @PathVariable("id") String id) {
        return ResponseEntity.ok(toCatalogItem(modelInstanceService.get(id)));
    }

    private Map<String, Object> toCatalogItem(ModelInstanceResponse item) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", item.getId());
        body.put("keySlug", item.getId());
        body.put("displayName", item.getName());
        body.put("name", item.getName());
        body.put("provider", item.getProvider());
        body.put("modelName", item.getModelName());
        body.put("modelType", item.getModelType());
        body.put("status", item.getStatus());
        body.put("lastTestStatus", item.getLastTestStatus());
        body.put("lastTestAt", item.getLastTestAt());
        body.put("lastTestLatencyMs", item.getLastTestLatencyMs());
        return body;
    }
}
