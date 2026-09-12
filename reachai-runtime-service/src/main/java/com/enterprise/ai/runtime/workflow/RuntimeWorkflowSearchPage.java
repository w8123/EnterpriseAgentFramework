package com.enterprise.ai.runtime.workflow;

import java.util.List;

public record RuntimeWorkflowSearchPage(
        List<RuntimeWorkflowDefinitionView> records,
        long total,
        int current,
        int size
) {
    public RuntimeWorkflowSearchPage {
        records = List.copyOf(records);
    }
}
