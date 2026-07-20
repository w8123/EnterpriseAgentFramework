package com.enterprise.ai.runtime.compat;

import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.enterprise.ai.runtime.execution.RuntimeCompositionExecutionService;
import com.enterprise.ai.runtime.execution.RuntimeInteractionResumeService;
import com.enterprise.ai.runtime.execution.interaction.WorkflowInteractionCodes;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
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

    /**
     * Legacy composition resume only. Workflow {@code wfi_} sessions must use authenticated Embed/Agent paths.
     */
    @PostMapping("/api/runtime/interactions/{sessionId}/resume")
    public ResponseEntity<Map<String, Object>> resumeInteraction(@PathVariable("sessionId") String sessionId,
                                                                 @RequestBody(required = false) Map<String, Object> body) {
        if (isWorkflowInteractionId(sessionId)) {
            Map<String, Object> forbidden = new LinkedHashMap<>();
            forbidden.put("success", false);
            forbidden.put("code", "RUNTIME_INTERACTION_FORBIDDEN");
            forbidden.put("answer", "Workflow interactions cannot be resumed via the public compatibility endpoint");
            forbidden.put("interactionId", sessionId);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(forbidden);
        }
        Map<String, Object> result = interactionResumeService.resume(sessionId, body == null ? Map.of() : body);
        String code = result == null ? null : String.valueOf(result.get("code"));
        if ("RUNTIME_INTERACTION_FORBIDDEN".equals(code)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(result);
        }
        if ("RUNTIME_INTERACTION_CONFLICT".equals(code)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(result);
        }
        if ("RUNTIME_INTERACTION_EXPIRED".equals(code)) {
            return ResponseEntity.status(HttpStatus.GONE).body(result);
        }
        return ResponseEntity.ok(result);
    }

    private boolean isWorkflowInteractionId(String sessionId) {
        return StringUtils.hasText(sessionId) && sessionId.trim().startsWith(WorkflowInteractionCodes.ID_PREFIX);
    }
}
