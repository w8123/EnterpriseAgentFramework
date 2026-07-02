package com.enterprise.ai.runtime.compat;

import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.enterprise.ai.runtime.execution.RuntimeCompositionExecutionService;
import com.enterprise.ai.runtime.execution.RuntimeInteractionResumeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequiredArgsConstructor
public class RuntimeCapabilityExecutionCompatibilityController {

    private final RuntimeCapabilityCatalogClient capabilityClient;
    private final RuntimeCompositionExecutionService compositionExecutionService;
    private final RuntimeInteractionResumeService interactionResumeService;

    @PostMapping("/api/runtime/tools/{qualifiedName}/execute")
    public ResponseEntity<Map<String, Object>> executeTool(@PathVariable("qualifiedName") String qualifiedName,
                                                           @RequestBody(required = false) Map<String, Object> body) {
        return ResponseEntity.ok(capabilityClient.executeTool(qualifiedName, body == null ? Map.of() : body));
    }

    @PostMapping("/api/runtime/compositions/{qualifiedName}/execute")
    public ResponseEntity<Map<String, Object>> executeComposition(@PathVariable("qualifiedName") String qualifiedName,
                                                                  @RequestBody(required = false) Map<String, Object> body) {
        return ResponseEntity.ok(compositionExecutionService.execute(qualifiedName, body == null ? Map.of() : body));
    }

    @PostMapping("/api/runtime/interactions/{sessionId}/resume")
    public ResponseEntity<Map<String, Object>> resumeInteraction(@PathVariable("sessionId") String sessionId,
                                                                 @RequestBody(required = false) Map<String, Object> body) {
        return ResponseEntity.ok(interactionResumeService.resume(sessionId, body == null ? Map.of() : body));
    }
}
