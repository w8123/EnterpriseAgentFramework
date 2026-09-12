package com.enterprise.ai.runtime.api;

import com.enterprise.ai.runtime.workflow.RuntimeWorkflowRevisionConflictException;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowRevisionFormatException;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowKeySlugConflictException;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowEditingException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice(assignableTypes = {
        RuntimeWorkflowPublicController.class,
        RuntimeWorkflowAiCodingPublicController.class,
        RuntimeWorkflowVersionPublicController.class
})
public class RuntimeWorkflowRevisionExceptionHandler {

    @ExceptionHandler(RuntimeWorkflowEditingException.class)
    public ResponseEntity<Map<String, Object>> handleEditingRejected(RuntimeWorkflowEditingException ex) {
        return ResponseEntity.badRequest().body(Map.of("code", ex.getCode(), "message", ex.getMessage()));
    }

    @ExceptionHandler(RuntimeWorkflowKeySlugConflictException.class)
    public ResponseEntity<Map<String, Object>> handleKeySlugConflict(RuntimeWorkflowKeySlugConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "code", "WORKFLOW_KEY_SLUG_EXISTS",
                "message", "Workflow Key 已存在，请使用其他 Key",
                "keySlug", ex.getKeySlug()));
    }

    @ExceptionHandler(RuntimeWorkflowRevisionConflictException.class)
    public ResponseEntity<Map<String, Object>> handleConflict(RuntimeWorkflowRevisionConflictException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "WORKFLOW_WORKING_COPY_CONFLICT");
        body.put("message", "Workflow working copy changed after it was loaded");
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
