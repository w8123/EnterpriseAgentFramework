package com.enterprise.ai.runtime.memory;

import org.springframework.http.HttpStatus;

public class RuntimeSessionRetentionException extends RuntimeException {

    private final String code;
    private final HttpStatus status;

    public RuntimeSessionRetentionException(String code, HttpStatus status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public String code() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }

    public static RuntimeSessionRetentionException notFound() {
        return new RuntimeSessionRetentionException(
                "RUNTIME_SESSION_NOT_FOUND", HttpStatus.NOT_FOUND, "Runtime session was not found");
    }

    public static RuntimeSessionRetentionException legalHold() {
        return new RuntimeSessionRetentionException(
                "RUNTIME_SESSION_LEGAL_HOLD", HttpStatus.LOCKED,
                "Runtime session is protected by legal hold");
    }

    public static RuntimeSessionRetentionException lifecycleBusy() {
        return new RuntimeSessionRetentionException(
                "RUNTIME_SESSION_LIFECYCLE_BUSY", HttpStatus.CONFLICT,
                "Runtime session lifecycle maintenance is already in progress");
    }

    public static RuntimeSessionRetentionException activeTurn() {
        return new RuntimeSessionRetentionException(
                "RUNTIME_SESSION_TURN_ACTIVE", HttpStatus.CONFLICT,
                "Runtime session has an active turn");
    }
}
