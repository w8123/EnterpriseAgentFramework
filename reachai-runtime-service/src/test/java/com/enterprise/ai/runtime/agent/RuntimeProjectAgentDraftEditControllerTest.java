package com.enterprise.ai.runtime.agent;

import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.runtime.agent.RuntimeAgentConfigViews.AgentDraftEditCommand;
import com.enterprise.ai.runtime.agent.RuntimeAgentConfigViews.AgentDraftVersionCommand;
import com.enterprise.ai.runtime.internalauth.VerifiedInternalServiceAuth;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RuntimeProjectAgentDraftEditControllerTest {
    private final RuntimeAgentConfigService configs = mock(RuntimeAgentConfigService.class);
    private final RuntimeProjectAgentDraftEditController controller = new RuntimeProjectAgentDraftEditController(configs);

    @Test void rejectsMissingPlatformOrMismatchedProjectIdentityBeforeOwnerCalls() {
        var request = new MockHttpServletRequest();
        var command = new AgentDraftEditCommand("agent-1", 7L, "orders", null, null);
        assertThrows(ResponseStatusException.class, () -> controller.context(request, command));
        for (var identity : java.util.List.of(
                new VerifiedInternalServiceAuth("control", InternalServiceAuthHeaders.IDENTITY_SOURCE_PLATFORM_SESSION, "orders", "42"),
                actor("billing", "ai-coding-" + "a".repeat(64)), actor("orders", "42"))) {
            request.setAttribute(VerifiedInternalServiceAuth.REQUEST_ATTR, identity);
            assertThrows(ResponseStatusException.class, () -> controller.context(request, command));
            assertThrows(ResponseStatusException.class, () -> controller.save(request, command));
            assertThrows(ResponseStatusException.class, () -> controller.versions(request, command));
        }
        verifyNoInteractions(configs);
    }

    @Test void delegatesRevisionCommandsAndDerivesPublicationActorFromVerifiedCredential() {
        var request = new MockHttpServletRequest();
        var identity = actor("orders", "ai-coding-" + "a".repeat(64));
        request.setAttribute(VerifiedInternalServiceAuth.REQUEST_ATTR, identity);
        var command = new AgentDraftEditCommand("agent-1", 7L, "orders", "b".repeat(64), null);
        var publish = new AgentDraftVersionCommand("agent-1", 7L, "orders", "b".repeat(64), 11L);
        controller.context(request, command);
        controller.save(request, command);
        controller.publish(request, publish);
        verify(configs).readEditableDraft(command);
        verify(configs).saveEditableDraft(command);
        String fingerprint = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                java.util.HexFormat.of().parseHex("a".repeat(64)));
        verify(configs).publishEditableDraft(publish, "project-key:" + fingerprint);
        verifyNoMoreInteractions(configs);
    }
    private VerifiedInternalServiceAuth actor(String tenant, String id) {
        return new VerifiedInternalServiceAuth(InternalServiceAuthHeaders.CALLER_CONTROL,
                InternalServiceAuthHeaders.IDENTITY_SOURCE_PROJECT_CREDENTIAL, tenant, id);
    }
}
