package com.enterprise.ai.runtime.supervisor;

import com.enterprise.ai.runtime.agent.RuntimeAgentWorkflowToolSnapshot;
import com.enterprise.ai.runtime.agent.RuntimeResolvedWorkflowTarget;
import com.enterprise.ai.runtime.workflow.RuntimeCapabilityContractPins;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Fail closed for historical or misconfigured Agent bindings before any business dispatch. */
@Component
@RequiredArgsConstructor
public class SupervisorBusinessMethodRiskGuard {
    private final RuntimeCapabilityContractPins pins;

    public void requireCompatible(RuntimeAgentWorkflowToolSnapshot tool, RuntimeResolvedWorkflowTarget target) {
        if (tool == null || target == null || target.version() == null
                || !StringUtils.hasText(target.version().getGraphSpecSnapshotJson())) {
            throw new IllegalArgumentException("BUSINESS_METHOD_WORKFLOW_RISK_UNVERIFIED");
        }
        String risk = tool.getRiskLevel();
        String floor;
        try {
            floor = pins.businessMethodRiskFloor(target.version().getGraphSpecSnapshotJson());
        } catch (Exception unavailableOrChanged) {
            throw new IllegalArgumentException("BUSINESS_METHOD_WORKFLOW_RISK_UNVERIFIED");
        }
        boolean readOnly = Boolean.TRUE.equals(tool.getReadOnly());
        boolean irreversible = "IRREVERSIBLE".equalsIgnoreCase(risk);
        boolean write = irreversible || "WRITE".equalsIgnoreCase(risk)
                || !StringUtils.hasText(risk) && !readOnly;
        if ("IRREVERSIBLE".equals(floor) && (readOnly || !irreversible)
                || "WRITE".equals(floor) && (readOnly || !write)) {
            throw new IllegalArgumentException("BUSINESS_METHOD_WORKFLOW_RISK_DOWNGRADE");
        }
    }
}
