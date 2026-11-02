package com.enterprise.ai.control.platform;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformConsolePermissionPolicy;
import com.enterprise.ai.control.identity.PlatformDelegatedRequestScope;
import com.enterprise.ai.control.mcp.application.port.McpPlatformEmbedPort;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/** Embed controllers rely on the console route rule, so this adapter applies the same rule before calling them. */
@Component
@RequiredArgsConstructor
class PlatformEmbedMcpAdapter implements McpPlatformEmbedPort {
    private final PlatformEmbedCatalogController catalog;
    private final PlatformEmbedCredentialPolicyController policies;
    private final PlatformDelegatedRequestScope scope;

    @Override
    public ResponseEntity<?> read(PlatformAuthenticatedSession actor, Read read, Map<String, Object> query) {
        admit(actor, "GET", "/api/platform/embed/" + read.name().toLowerCase());
        int limit = query.get("limit") instanceof Integer value ? value : 100;
        return scope.run(actor, request -> switch (read) {
            case SESSIONS -> catalog.listSessions(text(query, "appId"), text(query, "agentId"), text(query, "externalUserId"), text(query, "status"), limit);
            case PAGE_ACTION_EVENTS -> catalog.listPageActionEvents(text(query, "sessionId"), text(query, "appId"), text(query, "agentId"),
                    text(query, "status"), limit);
            case PAGES -> catalog.listPages(text(query, "projectCode"), limit);
            case PAGE_ACTIONS -> catalog.listPageActions(text(query, "projectCode"), text(query, "pageKey"), text(query, "status"), limit);
            case READINESS -> policies.readiness(text(query, "projectCode"), text(query, "agentId"), text(query, "origin"));
            case CREDENTIAL_POLICIES -> policies.listPolicies(text(query, "projectCode"), text(query, "status"), limit);
            case DEBUG_RESULT -> catalog.getPageActionDebugResult(text(query, "requestId"));
        });
    }

    @Override
    public ResponseEntity<?> change(PlatformAuthenticatedSession actor, Change change, long id, Map<String, Object> body) {
        String method = change == Change.DELETE_PAGE ? "DELETE" : "PUT";
        admit(actor, method, change == Change.DELETE_PAGE ? "/api/platform/embed/pages/" + id : "/api/platform/embed/credentials/" + id + "/policy");
        return scope.run(actor, request -> change == Change.DELETE_PAGE ? catalog.deletePageRegistry(id) : policies.updatePolicy(id, body));
    }

    private static void admit(PlatformAuthenticatedSession actor, String method, String path) {
        var rule = PlatformConsolePermissionPolicy.ruleFor(path).orElseThrow();
        if (actor == null || actor.user() == null || !PlatformConsolePermissionPolicy.admits(rule, method, path, actor)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "embed permission is required");
        }
    }

    private static String text(Map<String, Object> query, String key) {
        return query.get(key) instanceof String value ? value : null;
    }
}
