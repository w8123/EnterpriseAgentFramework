package com.enterprise.ai.control.a2a.domain.publication;

import com.enterprise.ai.control.a2a.domain.A2aDomainText;

public record A2aAgentCardSnapshot(
        String agentCardJson,
        String agentCardSha256,
        String securitySchemesJson,
        String securityRequirementsJson,
        String protocolSkillsJson,
        String defaultInputModesJson,
        String defaultOutputModesJson,
        String validationSummaryJson) {

    public A2aAgentCardSnapshot {
        agentCardJson = A2aDomainText.requireText(agentCardJson, "agentCardJson");
        agentCardSha256 = A2aDomainText.requireText(agentCardSha256, "agentCardSha256");
        protocolSkillsJson = A2aDomainText.requireText(protocolSkillsJson, "protocolSkillsJson");
        defaultInputModesJson = A2aDomainText.requireText(defaultInputModesJson, "defaultInputModesJson");
        defaultOutputModesJson = A2aDomainText.requireText(defaultOutputModesJson, "defaultOutputModesJson");
        validationSummaryJson = A2aDomainText.requireText(validationSummaryJson, "validationSummaryJson");
    }
}
