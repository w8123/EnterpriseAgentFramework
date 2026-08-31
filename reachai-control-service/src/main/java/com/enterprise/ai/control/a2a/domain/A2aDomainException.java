package com.enterprise.ai.control.a2a.domain;

import java.util.Objects;

/** A safe, stable domain failure that adapters may map to their own error model. */
public final class A2aDomainException extends RuntimeException {

    private final String code;

    public A2aDomainException(String code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
    }

    public String code() {
        return code;
    }
}
