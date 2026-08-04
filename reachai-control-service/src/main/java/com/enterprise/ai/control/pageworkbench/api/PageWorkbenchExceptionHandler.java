package com.enterprise.ai.control.pageworkbench.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice(assignableTypes = {
        PageWorkbenchConsoleController.class
})
public class PageWorkbenchExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException ex) {
        return error(HttpStatus.BAD_REQUEST, "PAGE_WORKBENCH_INVALID_REQUEST", ex.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> conflict(IllegalStateException ex) {
        return error(HttpStatus.CONFLICT, "PAGE_WORKBENCH_CONFLICT", ex.getMessage());
    }

    private static ResponseEntity<Map<String, Object>> error(
            HttpStatus status,
            String code,
            String message) {
        return ResponseEntity.status(status).body(Map.of(
                "schema", "reachai.page-workbench-error.v1",
                "code", code,
                "message", message == null ? "" : message));
    }
}
