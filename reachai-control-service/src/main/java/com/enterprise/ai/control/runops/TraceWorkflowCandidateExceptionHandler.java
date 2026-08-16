package com.enterprise.ai.control.runops;

import feign.FeignException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@Slf4j
@RestControllerAdvice(assignableTypes = TraceWorkflowCandidateController.class)
public class TraceWorkflowCandidateExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> invalid(IllegalArgumentException ex) {
        return error(HttpStatus.BAD_REQUEST,
                "TRACE_WORKFLOW_CANDIDATE_INELIGIBLE", ex.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> conflict(IllegalStateException ex) {
        return error(HttpStatus.CONFLICT,
                "TRACE_WORKFLOW_CANDIDATE_CONFLICT", ex.getMessage());
    }

    @ExceptionHandler(FeignException.class)
    public ResponseEntity<Map<String, Object>> unavailable(FeignException ex) {
        log.error("RunOps trace candidate dependency failed", ex);
        return error(HttpStatus.SERVICE_UNAVAILABLE,
                "TRACE_WORKFLOW_CANDIDATE_DEPENDENCY_UNAVAILABLE",
                "Runtime or Capability service is temporarily unavailable");
    }

    private static ResponseEntity<Map<String, Object>> error(
            HttpStatus status,
            String code,
            String message) {
        return ResponseEntity.status(status).body(Map.of(
                "schema", "reachai.runops.trace-workflow-candidate-error.v1",
                "code", code,
                "message", message == null ? "" : message));
    }
}
