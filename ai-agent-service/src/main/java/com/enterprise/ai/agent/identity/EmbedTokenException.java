package com.enterprise.ai.agent.identity;

public class EmbedTokenException extends RuntimeException {

    public EmbedTokenException(String message) {
        super(message);
    }

    public EmbedTokenException(String message, Throwable cause) {
        super(message, cause);
    }
}
