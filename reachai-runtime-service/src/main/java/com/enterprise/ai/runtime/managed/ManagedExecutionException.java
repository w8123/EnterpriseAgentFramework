package com.enterprise.ai.runtime.managed;

public class ManagedExecutionException extends RuntimeException {

    private final int status;
    private final String code;

    public ManagedExecutionException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }
}
