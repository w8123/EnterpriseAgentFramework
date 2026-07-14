package com.enterprise.ai.runtime.workflow;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.BAD_REQUEST)
public class RuntimeWorkflowRevisionFormatException extends IllegalArgumentException {

    public RuntimeWorkflowRevisionFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
