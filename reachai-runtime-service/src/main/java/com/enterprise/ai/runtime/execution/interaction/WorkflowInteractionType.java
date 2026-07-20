package com.enterprise.ai.runtime.execution.interaction;

import org.springframework.util.StringUtils;

import java.util.Locale;

public enum WorkflowInteractionType {
    COLLECT_INPUT,
    USER_CHOICE,
    CONFIRM_ACTION,
    PRESENT_OUTPUT,
    CUSTOM,
    REVIEW_EDIT;

    public boolean blocking() {
        return this != PRESENT_OUTPUT;
    }

    public static WorkflowInteractionType from(Object raw) {
        if (raw == null) {
            return COLLECT_INPUT;
        }
        String text = String.valueOf(raw).trim().replace('-', '_').toUpperCase(Locale.ROOT);
        if (!StringUtils.hasText(text)) {
            return COLLECT_INPUT;
        }
        try {
            return WorkflowInteractionType.valueOf(text);
        } catch (IllegalArgumentException ex) {
            return CUSTOM;
        }
    }
}
