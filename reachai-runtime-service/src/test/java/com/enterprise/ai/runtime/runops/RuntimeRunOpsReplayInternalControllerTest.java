package com.enterprise.ai.runtime.runops;

import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.runtime.identity.WorkflowExecutionIdentity;
import com.enterprise.ai.runtime.internalauth.VerifiedInternalServiceAuth;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RuntimeRunOpsReplayInternalControllerTest {
    @Test void unsignedAndRemoteRequestsCannotDelegateToReplay() {
        var service = mock(RuntimeRunOpsReplayService.class);
        var controller = new RuntimeRunOpsReplayInternalController(service);
        var request = new MockHttpServletRequest();
        var command = new RuntimeRunOpsReplayService.ReplayRequest("query", null, "forged", List.of("ADMIN"));
        assertEquals(401, controller.replay(request, "trace-1", command).getStatusCode().value());
        request.setAttribute(VerifiedInternalServiceAuth.REQUEST_ATTR,
                new VerifiedInternalServiceAuth("control", "A2A_REMOTE_AGENT", "default", "remote"));
        assertEquals(401, controller.replay(request, "trace-1", command).getStatusCode().value());
        verifyNoInteractions(service);
    }

    @Test void verifiedPlatformOperatorIsTheOnlyIdentityUsedByReplay() {
        var service = mock(RuntimeRunOpsReplayService.class);
        var controller = new RuntimeRunOpsReplayInternalController(service);
        var request = new MockHttpServletRequest();
        request.setAttribute(VerifiedInternalServiceAuth.REQUEST_ATTR, new VerifiedInternalServiceAuth(
                "control", InternalServiceAuthHeaders.IDENTITY_SOURCE_PLATFORM_SESSION, "default", "platform:7:operator"));
        var command = new RuntimeRunOpsReplayService.ReplayRequest("query", "new-session", "forged", List.of("PROJECT_OWNER"));
        assertEquals(200, controller.replay(request, "trace-1", command).getStatusCode().value());
        var identity = ArgumentCaptor.forClass(WorkflowExecutionIdentity.class);
        verify(service).replay(eq("trace-1"), eq(command), identity.capture());
        assertEquals("platform:7:operator", identity.getValue().userId());
        assertEquals("default", identity.getValue().tenantId());
        assertNull(identity.getValue().projectId());
        assertNull(identity.getValue().projectCode());
    }
}
