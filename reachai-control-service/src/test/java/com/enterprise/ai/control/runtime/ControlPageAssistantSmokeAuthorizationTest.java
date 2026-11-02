package com.enterprise.ai.control.runtime;

import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformPermissions;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ControlPageAssistantSmokeAuthorizationTest {
    @Test void checksWorkflowProjectPermissionBeforeUsingPageAuthorization() {
        var runtime = mock(RuntimeProxyClient.class);
        var access = mock(RuntimeManagementAccess.class);
        var gateway = mock(ControlPageAssistantSmokeGateway.class);
        var actor = mock(PlatformAuthenticatedSession.class);
        var controller = new ControlRuntimePublicController(runtime);
        controller.setRuntimeManagementAccess(access); controller.setPageAssistantSmokeGateway(gateway);
        var workflow = ResponseEntity.ok((Object) Map.of("id", "wf", "projectCode", "erp"));
        when(access.require(PlatformPermissions.WORKFLOW_DEBUG)).thenReturn(actor);
        when(runtime.getWorkflow("wf")).thenReturn(workflow);
        when(access.projectCode(workflow.getBody())).thenReturn("erp");
        var body = Map.<String, Object>of("runtimeContext", Map.of("embedSessionId", "embed-1"));
        when(gateway.execute("wf", "erp", "page-token", body)).thenReturn(ResponseEntity.ok(Map.of("status", "COMPLETED")));
        assertEquals(HttpStatus.OK, controller.smokeTestWorkflowAiCodingPageAssistant("wf", body, "page-token").getStatusCode());
        verify(access).requireResponseProject(actor, PlatformPermissions.WORKFLOW_DEBUG, workflow);
        verify(gateway).execute("wf", "erp", "page-token", body);
        reset(gateway);
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN)).when(access)
                .requireResponseProject(actor, PlatformPermissions.WORKFLOW_DEBUG, workflow);
        assertThrows(ResponseStatusException.class,
                () -> controller.smokeTestWorkflowAiCodingPageAssistant("wf", body, "page-token"));
        verifyNoInteractions(gateway);
    }
}
