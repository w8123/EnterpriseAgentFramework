package com.enterprise.ai.reach.spring;

import java.io.IOException;

/** Retains only the HTTP status; upstream bodies may contain sensitive data. */
final class ReachAiRegistryHttpException extends IOException {
    private final int status;

    ReachAiRegistryHttpException(int status) {
        super("ReachAI registry request failed: status=" + status);
        this.status = status;
    }

    int getStatus() {
        return status;
    }
}
