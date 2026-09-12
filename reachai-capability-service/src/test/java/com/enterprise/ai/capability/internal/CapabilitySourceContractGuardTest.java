package com.enterprise.ai.capability.internal;

import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionEntity;
import com.enterprise.ai.capability.registry.CapabilityChangeLifecycle;
import com.enterprise.ai.capability.registry.CapabilityChangePolicy;
import com.enterprise.ai.capability.registry.CapabilitySourceStateEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CapabilitySourceContractGuardTest {
    private final CapabilityChangeLifecycle lifecycle = mock(CapabilityChangeLifecycle.class);
    private final CapabilityChangePolicy policy = new CapabilityChangePolicy(new ObjectMapper());
    private final CapabilitySourceContractGuard guard = new CapabilitySourceContractGuard(lifecycle, policy);

    @Test
    void unknownOrMissingSourceCannotBeInvoked() {
        ToolDefinitionEntity tool = tool();
        assertEquals("CAPABILITY_SOURCE_UNKNOWN", assertThrows(CapabilityInvocationPolicyException.class,
                () -> guard.validate(tool, Map.of())).code());
        when(lifecycle.sourceState("orders:query")).thenReturn(new CapabilitySourceStateEntity());
        assertEquals("CAPABILITY_SOURCE_MISSING", assertThrows(CapabilityInvocationPolicyException.class,
                () -> guard.validate(tool, Map.of())).code());
    }

    @Test
    void ignoredDecisionOrStaleReadyLabelCannotHideContractDrift() {
        ToolDefinitionEntity tool = tool();
        CapabilitySourceStateEntity source = new CapabilitySourceStateEntity();
        source.setAvailability("READY");
        source.setSourceContractHash("different");
        source.setAcceptedContractHash(policy.contractHash(tool));
        when(lifecycle.sourceState("orders:query")).thenReturn(source);
        assertEquals("CAPABILITY_CONTRACT_DRIFT", assertThrows(CapabilityInvocationPolicyException.class,
                () -> guard.validate(tool, Map.of())).code());
    }

    @Test
    void acceptingNewCatalogDoesNotSilentlyChangePublishedWorkflowContract() {
        ToolDefinitionEntity tool = tool();
        String current = policy.contractHash(tool);
        CapabilitySourceStateEntity source = new CapabilitySourceStateEntity();
        source.setSourceContractHash(current);
        when(lifecycle.sourceState("orders:query")).thenReturn(source);
        assertDoesNotThrow(() -> guard.validate(tool, Map.of("expectedContractHash", current)));
        assertEquals("CAPABILITY_PUBLISHED_CONTRACT_CHANGED", assertThrows(CapabilityInvocationPolicyException.class,
                () -> guard.validate(tool, Map.of("expectedContractHash", "previous"))).code());
    }

    private ToolDefinitionEntity tool() {
        ToolDefinitionEntity tool = new ToolDefinitionEntity();
        tool.setQualifiedName("orders:query"); tool.setName("orders_query"); tool.setSourceLocation("sdk:orders:query");
        tool.setSourceQualifiedName("orders:query");
        tool.setEnabled(true); tool.setHttpMethod("POST"); tool.setEndpointPath("/query");
        tool.setBaseUrl("https://orders.example"); tool.setSideEffect("READ_ONLY");
        return tool;
    }

    @Test
    void clearingEditableSourceLocationDoesNotRemoveTheSourceContract() {
        ToolDefinitionEntity tool = tool();
        tool.setSourceLocation(null);
        CapabilitySourceStateEntity source = new CapabilitySourceStateEntity();
        source.setSourceContractHash("previous-contract");
        when(lifecycle.sourceState("orders:query")).thenReturn(source);
        assertEquals("CAPABILITY_CONTRACT_DRIFT", assertThrows(CapabilityInvocationPolicyException.class,
                () -> guard.validate(tool, Map.of())).code());
        verify(lifecycle).sourceState("orders:query");
    }

    @Test
    void renamedProjectionCannotEscapeItsSourceIdentity() {
        ToolDefinitionEntity tool = tool();
        tool.setSourceLocation(null);
        tool.setQualifiedName("manual:query");
        assertEquals("CAPABILITY_CONTRACT_DRIFT", assertThrows(CapabilityInvocationPolicyException.class,
                () -> guard.validate(tool, Map.of())).code());
    }

    @Test
    void legacySdkWithoutStableBindingRequiresTrustedSynchronization() {
        ToolDefinitionEntity tool = tool();
        tool.setSourceQualifiedName(null);
        assertEquals("SOURCE_UNKNOWN", guard.availability(tool));
    }
}
