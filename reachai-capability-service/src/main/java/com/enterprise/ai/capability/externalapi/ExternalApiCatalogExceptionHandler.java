package com.enterprise.ai.capability.externalapi;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice(assignableTypes = ExternalApiCatalogController.class)
public class ExternalApiCatalogExceptionHandler {

    @ExceptionHandler(ExternalApiCatalogException.class)
    public ResponseEntity<Map<String, Object>> handle(ExternalApiCatalogException exception) {
        return ResponseEntity.status(exception.status()).body(Map.of(
                "code", exception.code(),
                "message", exception.getMessage()
        ));
    }
}
