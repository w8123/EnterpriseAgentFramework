package com.enterprise.ai.runtime.automation;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice(assignableTypes = RuntimeAutomationInternalController.class)
public class RuntimeAutomationExceptionHandler {

    @ExceptionHandler(RuntimeAutomationException.class)
    public ResponseEntity<Map<String, Object>> automation(RuntimeAutomationException error) {
        return ResponseEntity.status(error.status()).body(Map.of(
                "success", false,
                "code", error.code(),
                "message", error.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> invalid(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "code", "AUTOMATION_REQUEST_INVALID",
                "message", error.getMessage() == null ? "Automation request is invalid" : error.getMessage()));
    }
}
