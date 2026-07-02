package com.enterprise.ai.runtime.compat;

import com.enterprise.ai.runtime.workflow.RuntimePageAssistantWorkflowBindingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequiredArgsConstructor
public class RuntimePageAssistantWorkflowCompatibilityController {

    private final RuntimePageAssistantWorkflowBindingService bindingService;

    @PostMapping("/api/workflows/{id}/page-assistant/bind")
    public ResponseEntity<?> bindPageAssistantWorkflow(
            @PathVariable String id,
            @RequestBody RuntimePageAssistantWorkflowBindRequest request) {
        try {
            return ResponseEntity.ok(bindingService.bindExistingPageWorkflow(id, request));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
        }
    }
}
