package com.enterprise.ai.capability.internal;

import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectToolEntity;
import com.enterprise.ai.capability.catalog.scan.CapabilityScanProjectCatalogService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class CapabilityProjectToolsInternalController {

    private final CapabilityScanProjectCatalogService scanProjectCatalogService;

    @GetMapping("/internal/capability/projects/by-id/{projectId}/tools")
    public ResponseEntity<?> listProjectTools(@PathVariable("projectId") Long projectId) {
        try {
            scanProjectCatalogService.get(projectId);
            List<Map<String, Object>> tools = scanProjectCatalogService.listTools(projectId).stream()
                    .filter(tool -> !Boolean.FALSE.equals(tool.getEnabled()))
                    .filter(tool -> !Boolean.TRUE.equals(tool.getRemovedFromSource()))
                    .map(this::toToolView)
                    .toList();
            return ResponseEntity.ok(tools);
        } catch (IllegalArgumentException ex) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("service", "reachai-capability-service");
            body.put("code", "CAPABILITY_PROJECT_NOT_FOUND");
            body.put("projectId", projectId);
            body.put("message", ex.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
        }
    }

    private Map<String, Object> toToolView(ScanProjectToolEntity tool) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("toolId", tool.getId());
        body.put("keySlug", tool.getName());
        body.put("displayName", firstText(tool.getName()));
        body.put("description", firstText(tool.getAiDescription(), tool.getDescription()));
        body.put("status", Boolean.TRUE.equals(tool.getEnabled()) ? "ACTIVE" : "DISABLED");
        body.put("inputSchema", tool.getParametersJson());
        body.put("source", tool.getSource());
        body.put("capabilityType", "HTTP_TOOL");
        body.put("httpMethod", tool.getHttpMethod());
        body.put("endpointPath", tool.getEndpointPath());
        return body;
    }

    private String firstText(String... values) {
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
