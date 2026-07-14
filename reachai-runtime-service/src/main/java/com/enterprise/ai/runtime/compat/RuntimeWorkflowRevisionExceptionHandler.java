package com.enterprise.ai.runtime.compat;

import com.enterprise.ai.runtime.workflow.RuntimeWorkflowRevisionConflictException;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowRevisionFormatException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice(assignableTypes = {
        RuntimeWorkflowCompatibilityController.class,
        RuntimeWorkflowAiCodingCompatibilityController.class,
        RuntimeWorkflowVersionCompatibilityController.class
})
public class RuntimeWorkflowRevisionExceptionHandler {

    @ExceptionHandler(RuntimeWorkflowRevisionConflictException.class)
    public ResponseEntity<Map<String, Object>> handleConflict(RuntimeWorkflowRevisionConflictException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "WORKFLOW_DRAFT_CONFLICT");
        body.put("message", "Workflow draft changed after it was loaded");
        body.put("workflowId", ex.getWorkflowId());
        body.put("baseRevision", ex.getBaseRevision());
        body.put("currentRevision", ex.getCurrentRevision());
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.CONFLICT);
        if (ex.getCurrentRevision() != null && !ex.getCurrentRevision().isBlank()) {
            response.header(HttpHeaders.ETAG, "\"" + ex.getCurrentRevision() + "\"");
        }
        return response.body(body);
    }

    @ExceptionHandler(RuntimeWorkflowRevisionFormatException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidRevision(RuntimeWorkflowRevisionFormatException ex) {
        return ResponseEntity.badRequest().body(Map.of(
                "code", "WORKFLOW_BASE_REVISION_INVALID",
                "message", ex.getMessage()));
    }
}
