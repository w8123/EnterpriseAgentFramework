package com.enterprise.ai.runtime.agent;

import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.runtime.agent.RuntimeAgentConfigViews.AgentDraftEditCommand;
import com.enterprise.ai.runtime.internalauth.VerifiedInternalServiceAuth;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class RuntimeAgentDraftEditControllerTest {
    @Test void reportsRiskDowngradesAsBadRequestsWithoutExposingOtherErrors() {
        var controller = new RuntimeAgentDraftEditController(mock(RuntimeAgentConfigService.class));
        var rejected = controller.invalid(new IllegalArgumentException("BUSINESS_METHOD_WORKFLOW_RISK_DOWNGRADE: details"));
        assertEquals(400, rejected.getStatusCode().value());
        assertEquals("BUSINESS_METHOD_WORKFLOW_RISK_DOWNGRADE", rejected.getBody().get("code"));
        assertEquals("AGENT_DRAFT_INVALID", controller.invalid(new IllegalArgumentException("private details")).getBody().get("code"));
    }
    @Test void rejectsMissingActorAndAProjectThatDiffersFromTheSignedTenant() {
        var service = mock(RuntimeAgentConfigService.class);
        var controller = new RuntimeAgentDraftEditController(service);
        var request = new MockHttpServletRequest();
        var command = new AgentDraftEditCommand("agent-1", 7L, "orders", null, null);
        assertThrows(ResponseStatusException.class, () -> controller.context(request, command));
        request.setAttribute(VerifiedInternalServiceAuth.REQUEST_ATTR, new VerifiedInternalServiceAuth(
                InternalServiceAuthHeaders.CALLER_CONTROL, InternalServiceAuthHeaders.IDENTITY_SOURCE_PLATFORM_SESSION,
                "billing", "42"));
        assertThrows(ResponseStatusException.class, () -> controller.context(request, command));
        verify(service, never()).readEditableDraft(command);
    }
}
