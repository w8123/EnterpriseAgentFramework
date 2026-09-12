package com.enterprise.ai.runtime.workflow;

import java.util.Collection;
import java.util.Set;

/**
 * References that prevent Workflow deletion. Implementations must include retained draft,
 * disabled and historical bindings, not only bindings available for published execution.
 */
public interface RuntimeWorkflowDeletionReferences {
    Set<String> referencedWorkflowIds(Collection<String> workflowIds);
}
