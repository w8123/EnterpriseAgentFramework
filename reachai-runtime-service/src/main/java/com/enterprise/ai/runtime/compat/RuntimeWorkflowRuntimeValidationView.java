package com.enterprise.ai.runtime.compat;

import java.util.List;

public record RuntimeWorkflowRuntimeValidationView(
        boolean valid,
        List<Item> errors) {

    public record Item(
            String code,
            String target,
            String message) {
    }
}
