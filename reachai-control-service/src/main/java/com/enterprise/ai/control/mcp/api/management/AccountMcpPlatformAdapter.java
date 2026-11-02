package com.enterprise.ai.control.mcp.api.management;

import com.enterprise.ai.control.identity.PlatformAccountRoleController;
import com.enterprise.ai.control.identity.PlatformAccountRoleController.AccountUserUpdateCommand;
import com.enterprise.ai.control.identity.PlatformAccountRoleController.RoleCreateCommand;
import com.enterprise.ai.control.identity.PlatformAccountRoleController.RoleGrantCommand;
import com.enterprise.ai.control.identity.PlatformAccountRoleController.RoleUpdateCommand;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformDelegatedRequestScope;
import com.enterprise.ai.control.mcp.application.port.McpPlatformAccountPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** Runs platform AI account administration through the controller a platform administrator uses in the console. */
@Component
@RequiredArgsConstructor
class AccountMcpPlatformAdapter implements McpPlatformAccountPort {
    private final PlatformAccountRoleController accounts;
    private final PlatformDelegatedRequestScope scope;
    private final ObjectMapper json;

    @Override
    public ResponseEntity<?> call(PlatformAuthenticatedSession actor, Operation operation, Long id, Map<String, Object> query, Object body) {
        return scope.run(actor, request -> switch (operation) {
            case USERS -> accounts.listUsers(request);
            case ROLES -> accounts.listRoles(request);
            case PERMISSIONS -> accounts.listPermissions(request);
            case AUDIT_EVENTS -> accounts.listAuditEvents(request, (String) query.get("eventType"), (String) query.get("targetType"),
                    query.get("limit") instanceof Integer limit ? limit : 100);
            case UPDATE_USER -> accounts.updateUser(request, id, json.convertValue(body, AccountUserUpdateCommand.class));
            case REVOKE_SESSIONS -> accounts.revokeUserSessions(request, id);
            case REPLACE_GRANTS -> accounts.saveUserGrants(request, id, List.of(json.convertValue(body, RoleGrantCommand[].class)));
            case CREATE_ROLE -> accounts.createRole(request, json.convertValue(body, RoleCreateCommand.class));
            case UPDATE_ROLE -> accounts.updateRole(request, id, json.convertValue(body, RoleUpdateCommand.class));
        });
    }
}
