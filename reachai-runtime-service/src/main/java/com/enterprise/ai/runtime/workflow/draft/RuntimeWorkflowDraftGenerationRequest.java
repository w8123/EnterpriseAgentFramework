package com.enterprise.ai.runtime.workflow.draft;

import java.util.List;
import java.util.Map;

public record RuntimeWorkflowDraftGenerationRequest(
        String agentId,
        String agentName,
        String requirement,
        String projectCode,
        String modelInstanceId,
        String draftScenario,
        Map<String, Object> currentCanvas,
        List<RuntimeWorkflowDraftResourceView> tools,
        List<RuntimeWorkflowDraftResourceView> capabilities,
        List<RuntimeWorkflowDraftResourceView> knowledgeBases,
        List<RuntimeWorkflowDraftResourceView> pageActions) {

    public String getAgentId() {
        return agentId;
    }

    public String getAgentName() {
        return agentName;
    }

    public String getRequirement() {
        return requirement;
    }

    public String getProjectCode() {
        return projectCode;
    }

    public String getModelInstanceId() {
        return modelInstanceId;
    }

    public String getDraftScenario() {
        return draftScenario;
    }

    public Map<String, Object> getCurrentCanvas() {
        return currentCanvas;
    }

    public List<RuntimeWorkflowDraftResourceView> getTools() {
        return tools;
    }

    public List<RuntimeWorkflowDraftResourceView> getCapabilities() {
        return capabilities;
    }

    public List<RuntimeWorkflowDraftResourceView> getKnowledgeBases() {
        return knowledgeBases;
    }

    public List<RuntimeWorkflowDraftResourceView> getPageActions() {
        return pageActions;
    }
}
