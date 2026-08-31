package com.enterprise.ai.capability.externalapi;

import org.springframework.http.HttpStatus;

public class ExternalApiCatalogException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ExternalApiCatalogException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
