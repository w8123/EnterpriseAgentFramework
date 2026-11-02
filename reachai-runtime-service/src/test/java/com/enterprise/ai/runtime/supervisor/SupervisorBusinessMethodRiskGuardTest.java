package com.enterprise.ai.runtime.supervisor;

import com.enterprise.ai.runtime.agent.RuntimeAgentWorkflowToolSnapshot;
import com.enterprise.ai.runtime.agent.RuntimeResolvedWorkflowTarget;
import com.enterprise.ai.runtime.workflow.RuntimeCapabilityContractPins;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowPublishedVersionView;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SupervisorBusinessMethodRiskGuardTest {
    private final RuntimeCapabilityContractPins pins = mock(RuntimeCapabilityContractPins.class);
    private final SupervisorBusinessMethodRiskGuard guard = new SupervisorBusinessMethodRiskGuard(pins);
    private final RuntimeResolvedWorkflowTarget target = new RuntimeResolvedWorkflowTarget(null, null,
            RuntimeWorkflowPublishedVersionView.builder().graphSpecSnapshotJson("{\"nodes\":[]}").build());

    @Test
    void rejectsDowngradedLegacyBindingAndUnavailableOwner() {
        var read = RuntimeAgentWorkflowToolSnapshot.builder().riskLevel("READ").readOnly(true).build();
        when(pins.businessMethodRiskFloor(anyString())).thenReturn("WRITE");
        assertEquals("BUSINESS_METHOD_WORKFLOW_RISK_DOWNGRADE",
                assertThrows(IllegalArgumentException.class, () -> guard.requireCompatible(read, target)).getMessage());
        when(pins.businessMethodRiskFloor(anyString())).thenThrow(new IllegalStateException("private"));
        assertEquals("BUSINESS_METHOD_WORKFLOW_RISK_UNVERIFIED",
                assertThrows(IllegalArgumentException.class, () -> guard.requireCompatible(read, target)).getMessage());
    }

    @Test
    void permitsVerifiedReadAndExplicitlyConfirmedWrite() {
        when(pins.businessMethodRiskFloor(anyString())).thenReturn("READ");
        var read = RuntimeAgentWorkflowToolSnapshot.builder().riskLevel("READ").readOnly(true).build();
        assertDoesNotThrow(() -> guard.requireCompatible(read, target));
        var write = RuntimeAgentWorkflowToolSnapshot.builder().riskLevel("WRITE").readOnly(false).build();
        assertDoesNotThrow(() -> guard.requireCompatible(write, target));
        verify(pins, times(2)).businessMethodRiskFloor(anyString());
    }

    @Test
    void rejectsIrreversibleOperationDowngradedToWrite() {
        when(pins.businessMethodRiskFloor(anyString())).thenReturn("IRREVERSIBLE");
        var write = RuntimeAgentWorkflowToolSnapshot.builder().riskLevel("WRITE").readOnly(false).build();
        assertThrows(IllegalArgumentException.class, () -> guard.requireCompatible(write, target));
        var irreversible = RuntimeAgentWorkflowToolSnapshot.builder().riskLevel("IRREVERSIBLE").readOnly(false).build();
        assertDoesNotThrow(() -> guard.requireCompatible(irreversible, target));
    }
}
