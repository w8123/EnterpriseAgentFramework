package com.enterprise.ai.control.mcp.api.management;

import com.enterprise.ai.control.identity.BusinessUserDirectoryController;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformDelegatedRequestScope;
import com.enterprise.ai.control.identity.RuntimeUserMappingController;
import com.enterprise.ai.control.mcp.application.port.McpPlatformIdentityPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Runs platform AI identity mapping calls through the controllers a person uses on the identity pages. */
@Component
@RequiredArgsConstructor
class IdentityMcpPlatformAdapter implements McpPlatformIdentityPort {
    private final RuntimeUserMappingController mappings;
    private final BusinessUserDirectoryController businessUsers;
    private final PlatformDelegatedRequestScope scope;
    private final ObjectMapper json;

    @Override
    public ResponseEntity<?> call(PlatformAuthenticatedSession actor, Operation operation, Long id, Map<String, Object> query, Map<String, Object> body) {
        return scope.run(actor, request -> switch (operation) {
            case RUNTIME_MAPPINGS -> mappings.list(request, (String) query.get("tenantId"), (Long) query.get("platformUserId"),
                    (String) query.get("runtimeUserId"), null, (String) query.get("projectCode"), (String) query.get("status"),
                    query.get("limit") instanceof Integer limit ? limit : 100);
            case BUSINESS_USERS -> businessUsers.listBusinessUsers(request, query.get("current") instanceof Integer current ? current : 1,
                    query.get("size") instanceof Integer size ? size : 20, (String) query.getOrDefault("tenantId", "default"),
                    (String) query.get("keyword"), (String) query.get("status"));
            case BUSINESS_USER_IDENTITIES -> businessUsers.listBusinessUserIdentities(request, id);
            case MAPPING_CREATE -> mappings.create(request, json.convertValue(body, RuntimeUserMappingController.CreateCommand.class));
            case MAPPING_DELETE -> mappings.delete(request, id);
            case BUSINESS_IDENTITY_SAVE -> businessUsers.saveBusinessUserIdentity(request, id,
                    json.convertValue(body, BusinessUserDirectoryController.ExternalIdentityCommand.class));
        });
    }
}
