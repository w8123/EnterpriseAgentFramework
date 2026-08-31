package com.enterprise.ai.runtime.automation;

import org.springframework.http.HttpStatus;

public final class RuntimeAutomationException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public RuntimeAutomationException(HttpStatus status, String code, String message) {
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

    static RuntimeAutomationException badRequest(String code, String message) {
        return new RuntimeAutomationException(HttpStatus.BAD_REQUEST, code, message);
    }

    static RuntimeAutomationException notFound(String message) {
        return new RuntimeAutomationException(HttpStatus.NOT_FOUND, "AUTOMATION_NOT_FOUND", message);
    }

    static RuntimeAutomationException conflict(String code, String message) {
        return new RuntimeAutomationException(HttpStatus.CONFLICT, code, message);
    }
}
