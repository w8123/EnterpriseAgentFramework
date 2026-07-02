package com.enterprise.ai.runtime.workflow;

import java.util.ArrayList;
import java.util.List;

public record RuntimeWorkflowReleaseValidationResult(
        boolean valid,
        List<Item> errors,
        List<Item> warnings
) {

    public static Builder builder() {
        return new Builder();
    }

    public record Item(
            String code,
            String level,
            String nodeId,
            String message
    ) {
    }

    public static class Builder {
        private final List<Item> errors = new ArrayList<>();
        private final List<Item> warnings = new ArrayList<>();

        public Builder error(String code, String nodeId, String message) {
            errors.add(new Item(code, "ERROR", nodeId, message));
            return this;
        }

        public Builder warn(String code, String nodeId, String message) {
            warnings.add(new Item(code, "WARN", nodeId, message));
            return this;
        }

        public RuntimeWorkflowReleaseValidationResult build() {
            return new RuntimeWorkflowReleaseValidationResult(errors.isEmpty(), List.copyOf(errors), List.copyOf(warnings));
        }
    }
}
