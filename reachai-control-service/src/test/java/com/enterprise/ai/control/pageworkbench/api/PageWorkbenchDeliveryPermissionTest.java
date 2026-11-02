package com.enterprise.ai.control.pageworkbench.api;

import com.enterprise.ai.control.identity.*;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchWorkflowDeliveryApplicationService.DeliveryCommand;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PageWorkbenchDeliveryPermissionTest {
    @Test void deliveryNeedsWorkflowPublishAndAgentPublishWhenAttachingAnAgent() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE, session(
                new PlatformPermissionGrant("platform:write", "PROJECT", "orders"),
                new PlatformPermissionGrant("workflow:publish", "PROJECT", "orders")));
        assertDoesNotThrow(() -> PageWorkbenchConsoleController.requireDeliveryPermission(request, "orders",
                new DeliveryCommand(null, null, null, null, null)));
        var noAgentPublish = assertThrows(ResponseStatusException.class, () -> PageWorkbenchConsoleController.requireDeliveryPermission(request, "orders",
                new DeliveryCommand(null, null, "agent-1", null, null)));
        assertEquals(403, noAgentPublish.getStatusCode().value());
        var otherProject = assertThrows(ResponseStatusException.class, () -> PageWorkbenchConsoleController.requireDeliveryPermission(request, "billing",
                new DeliveryCommand(null, null, null, null, null)));
        assertEquals(403, otherProject.getStatusCode().value());
    }

    private static PlatformAuthenticatedSession session(PlatformPermissionGrant... grants) {
        return new PlatformAuthenticatedSession(new PlatformPrincipal(42L, "operator", "操作员"), "session", null, List.of(),
                List.of("platform:write", "workflow:publish"), List.of(grants));
    }
}
