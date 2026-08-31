package com.enterprise.ai.runtime.managed;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice(basePackageClasses = ManagedExecutionInternalController.class)
public class ManagedExecutionExceptionHandler {

    @ExceptionHandler(ManagedExecutionException.class)
    public ResponseEntity<Map<String, Object>> managedExecution(ManagedExecutionException failure) {
        return ResponseEntity.status(failure.status()).body(Map.of(
                "success", false,
                "code", failure.code(),
                "message", failure.getMessage()));
    }
}
