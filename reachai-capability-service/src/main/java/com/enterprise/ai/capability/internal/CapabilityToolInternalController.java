package com.enterprise.ai.capability.internal;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class CapabilityToolInternalController {

    private final CapabilityToolLookupService lookupService;
    private final CapabilityToolExecutionService executionService;

    @GetMapping("/internal/capability/tools/{qualifiedName}")
    public ResponseEntity<Map<String, Object>> getToolDefinition(@PathVariable("qualifiedName") String qualifiedName) {
        try {
            return ResponseEntity.ok(lookupService.getToolDefinition(qualifiedName));
        } catch (IllegalArgumentException ex) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("service", "reachai-capability-service");
            body.put("code", "CAPABILITY_TOOL_NOT_FOUND");
            body.put("qualifiedName", qualifiedName);
            body.put("message", ex.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
        }
    }

    @PostMapping("/internal/capability/tools/{qualifiedName}/execute")
    public ResponseEntity<Map<String, Object>> executeTool(@PathVariable("qualifiedName") String qualifiedName,
                                                           @RequestBody(required = false) Map<String, Object> request) {
        try {
            return ResponseEntity.ok(executionService.execute(qualifiedName, request == null ? Map.of() : request));
        } catch (IllegalArgumentException ex) {
            Map<String, Object> body = error("CAPABILITY_TOOL_NOT_FOUND", qualifiedName, ex.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
        } catch (IllegalStateException ex) {
            Map<String, Object> body = error("CAPABILITY_TOOL_EXECUTION_REJECTED", qualifiedName, ex.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
        }
    }

    private Map<String, Object> error(String code, String qualifiedName, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("service", "reachai-capability-service");
        body.put("code", code);
        body.put("qualifiedName", qualifiedName);
        body.put("message", message);
        return body;
    }
}
