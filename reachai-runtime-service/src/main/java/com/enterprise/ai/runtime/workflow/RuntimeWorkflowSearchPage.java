package com.enterprise.ai.runtime.workflow;

import java.util.List;

public record RuntimeWorkflowSearchPage(
        List<RuntimeWorkflowDefinitionEntity> records,
        long total,
        int current,
        int size
) {
}
