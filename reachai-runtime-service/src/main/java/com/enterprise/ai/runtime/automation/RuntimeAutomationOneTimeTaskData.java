package com.enterprise.ai.runtime.automation;

import java.io.Serial;
import java.io.Serializable;

record RuntimeAutomationOneTimeTaskData(Long automationId,
                                        Long automationVersionId) implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;
}
