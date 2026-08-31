package com.enterprise.ai.control.mcp.domain;

import java.util.Objects;

/** A safe, stable domain failure that adapters may map to their own error model. */
public class McpDomainException extends RuntimeException {

    private final String code;

    public McpDomainException(String code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
    }

    public String code() {
        return code;
    }
}
