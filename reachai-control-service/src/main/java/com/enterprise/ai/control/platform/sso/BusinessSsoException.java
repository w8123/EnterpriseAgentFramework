package com.enterprise.ai.control.platform.sso;

/** A business-system sign-in failure whose message is safe to show the person signing in. */
public class BusinessSsoException extends RuntimeException {
    public BusinessSsoException(String message) {
        super(message);
    }
}
