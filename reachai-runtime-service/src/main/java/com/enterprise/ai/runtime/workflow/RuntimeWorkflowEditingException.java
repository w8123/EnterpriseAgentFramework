package com.enterprise.ai.runtime.workflow;

import lombok.Getter;

@Getter
public class RuntimeWorkflowEditingException extends IllegalArgumentException {
    private final String code;

    public RuntimeWorkflowEditingException(String code, String message) {
        super(message);
        this.code = code;
    }
}
