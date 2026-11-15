package com.enterprise.ai.external;

/** A user-readable failure talking to an external knowledge base. The message never contains credentials. */
public class ExternalKnowledgeException extends RuntimeException {
    public ExternalKnowledgeException(String message) {
        super(message);
    }
}
