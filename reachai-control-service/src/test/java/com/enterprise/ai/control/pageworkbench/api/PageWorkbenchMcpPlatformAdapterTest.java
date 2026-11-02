package com.enterprise.ai.control.pageworkbench.api;

import com.enterprise.ai.control.compat.RegistryInstanceConsoleOperations;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformDelegatedRequestScope;
import com.enterprise.ai.control.identity.PlatformPermissionGrant;
import com.enterprise.ai.control.identity.PlatformPrincipal;
import com.enterprise.ai.control.mcp.application.port.McpPlatformProjectPagesPort.Read;
import com.enterprise.ai.control.mcp.application.port.McpPlatformProjectPagesPort.Write;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PageWorkbenchMcpPlatformAdapterTest {
    private final PageWorkbenchConsoleController workbench = mock(PageWorkbenchConsoleController.class);
    private final RegistryInstanceConsoleOperations instances = mock(RegistryInstanceConsoleOperations.class);
    private final PageWorkbenchMcpPlatformAdapter adapter = new PageWorkbenchMcpPlatformAdapter(workbench, instances,
            new PlatformDelegatedRequestScope(), new ObjectMapper());
    // Platform write only inside project "orders".
    private final PlatformAuthenticatedSession actor = new PlatformAuthenticatedSession(new PlatformPrincipal(7L, "builder", "Builder"),
            "s", LocalDateTime.now().plusHours(1), List.of(), List.of("platform:read", "platform:write"),
            List.of(new PlatformPermissionGrant("platform:read", "PROJECT", "orders"), new PlatformPermissionGrant("platform:write", "PROJECT", "orders")));

    @AfterEach void clearRequest() { RequestContextHolder.resetRequestAttributes(); }

    @Test
    void anotherProjectIsRefusedBeforeAnyController() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest("POST", "/mcp/platform")));
        assertThrows(ResponseStatusException.class, () -> adapter.read(actor, "finance", Read.PAGES, Map.of()));
        assertThrows(ResponseStatusException.class, () -> adapter.write(actor, "finance", Write.PURGE_OFFLINE_INSTANCES, null, Map.of()));
        verifyNoInteractions(workbench, instances);
    }

    @Test
    void findingReviewerIsTheCurrentAccount() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest("POST", "/mcp/platform")));
        adapter.write(actor, "orders", Write.FINDING_STATUS, 5L, Map.of("status", "KEPT", "reviewedBy", "someone-else"));
        verify(workbench).updateFindingStatus("orders", 5L, new PageWorkbenchConsoleController.FindingStatusRequest("KEPT", "builder"));

        adapter.write(actor, "orders", Write.INSTANCE_STATUS, null, Map.of("instanceId", "i-1", "status", "DISABLED"));
        verify(instances).change(any(), eq("orders"), eq("status"), eq(Map.of("instanceId", "i-1", "status", "DISABLED")));
    }
}
