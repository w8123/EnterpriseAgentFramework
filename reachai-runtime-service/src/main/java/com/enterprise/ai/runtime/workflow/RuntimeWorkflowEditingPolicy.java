package com.enterprise.ai.runtime.workflow;

/** Both human and AI draft writers must respect the definition's owning source. */
public final class RuntimeWorkflowEditingPolicy {
    private RuntimeWorkflowEditingPolicy() { }

    public static void requireUserEditable(RuntimeWorkflowDefinitionEntity workflow) {
        if (workflow == null || !"USER".equals(workflow.getDefinitionAuthority())
                || "AI_QUICK_ACCESS".equals(workflow.getCreationChannel())) {
            throw new RuntimeWorkflowEditingException("WORKFLOW_DEFINITION_READ_ONLY",
                    "该 Workflow 由其他入口维护，请从其定义来源更新");
        }
    }
}
