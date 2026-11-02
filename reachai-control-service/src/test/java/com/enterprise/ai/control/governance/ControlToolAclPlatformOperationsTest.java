package com.enterprise.ai.control.governance;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformPermissionGrant;
import com.enterprise.ai.control.identity.PlatformPrincipal;
import com.enterprise.ai.control.identity.PlatformUserEntity;
import com.enterprise.ai.control.governance.ControlToolAclPlatformOperations.Operation;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ControlToolAclPlatformOperationsTest {
    private final ControlToolAclController controller = mock(ControlToolAclController.class);
    private final ControlToolAclPlatformOperations adapter = new ControlToolAclPlatformOperations(controller, mock(ControlToolAclMapper.class));

    @Test
    void readersCannotWriteAndWritesStateTheProjectScopeExplicitly() {
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class, () -> adapter.change(actor("tool-acl:read"),
                Operation.DELETE, 5L, Map.of())).getStatusCode());
        verifyNoInteractions(controller);

        var rule = new java.util.HashMap<String, Object>();
        rule.put("roleCode", "sales"); rule.put("projectId", null); rule.put("projectCode", null); rule.put("targetKind", "TOOL");
        rule.put("targetName", "create_customer"); rule.put("permission", "DENY"); rule.put("note", null); rule.put("enabled", true);
        adapter.change(actor("tool-acl:manage"), Operation.UPDATE, 5L, rule);
        verify(controller).update(eq(5L), argThat(request -> request.projectIdPresent() && request.projectCodePresent()
                && "DENY".equals(request.getPermission()) && request.getProjectId() == null));
    }

    private static PlatformAuthenticatedSession actor(String permission) {
        var user = new PlatformUserEntity();
        user.setId(7L);
        return new PlatformAuthenticatedSession(PlatformPrincipal.fromUser(user), "pai_19", LocalDateTime.now().plusDays(1),
                List.of(), List.of(permission), List.of(new PlatformPermissionGrant(permission, "GLOBAL", null)));
    }
}
