package com.enterprise.ai.control.runtime;

import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ControlAgentLegacyDraftWriteTest {
    @Test void obsoleteCommandsCannotFetchFreshRevisionValidateBindingsOrWrite() {
        var runtime = mock(RuntimeProxyClient.class);
        var controller = new ControlRuntimePublicController(runtime);
        var request = new MockHttpServletRequest();
        for (var result : java.util.List.of(
                controller.saveAgentConfigDraft(request, "agent-1", Map.of("baseRevision", "a".repeat(64))),
                controller.publishAgentConfigVersion(request, "agent-1", 31L, Map.of("publishedBy", "forged")),
                controller.copyAgentConfigVersionToDraft("agent-1", 31L))) {
            assertEquals(409, result.getStatusCode().value());
            assertEquals("AGENT_DRAFT_REVISION_REQUIRED", ((Map<?, ?>) result.getBody()).get("code"));
        }
        verifyNoInteractions(runtime);
    }

    @Test void obsoleteRoutesRetainPermissionEnforcementBeforeMigrationResponse() {
        var runtime = mock(RuntimeProxyClient.class);
        var controller = new ControlRuntimePublicController(runtime);
        var access = mock(RuntimeManagementAccess.class);
        controller.setRuntimeManagementAccess(access);
        when(access.require(anyString())).thenThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN));
        var request = new MockHttpServletRequest();
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> controller.saveAgentConfigDraft(request, "agent-1", Map.of()));
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> controller.publishAgentConfigVersion(request, "agent-1", 31L, Map.of()));
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> controller.copyAgentConfigVersionToDraft("agent-1", 31L));
        verifyNoInteractions(runtime);
    }
}
