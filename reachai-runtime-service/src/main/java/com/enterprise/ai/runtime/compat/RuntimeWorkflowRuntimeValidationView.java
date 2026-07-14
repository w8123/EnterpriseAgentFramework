package com.enterprise.ai.runtime.compat;

import java.util.List;

public record RuntimeWorkflowRuntimeValidationView(
        boolean valid,
        List<Item> errors,
        List<Item> warnings) {

    public RuntimeWorkflowRuntimeValidationView(boolean valid, List<Item> errors) {
        this(valid, errors, List.of());
    }

    public record Item(
            String code,
            String target,
            String message) {
    }
}
