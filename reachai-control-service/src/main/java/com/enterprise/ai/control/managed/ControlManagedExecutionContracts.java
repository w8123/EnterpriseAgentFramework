package com.enterprise.ai.control.managed;

import com.fasterxml.jackson.databind.JsonNode;

public final class ControlManagedExecutionContracts {

    private ControlManagedExecutionContracts() {
    }

    public record EventRequest(
            String schema,
            String eventId,
            String executionId,
            String eventType,
            JsonNode payload) {
    }

    public record EventResponse(
            String schema,
            String eventId,
            boolean accepted,
            boolean idempotentReplay) {
    }
}
