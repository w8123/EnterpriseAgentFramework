package com.enterprise.ai.control.mcp.api.management;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformDelegatedRequestScope;
import com.enterprise.ai.control.mcp.application.port.McpPlatformPublicationPort;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** Runs platform AI publication calls through the same controllers a person uses in the MCP Hub console. */
@Component
@RequiredArgsConstructor
class McpPublicationPlatformAdapter implements McpPlatformPublicationPort {
    private final McpPublicationController publications;
    private final McpClientController clients;
    private final McpEmployeeAccessController employeeAccess;
    private final PlatformDelegatedRequestScope scope;

    @Override
    public ResponseEntity<?> read(PlatformAuthenticatedSession actor, Read operation, Long publicationId, Map<String, Object> query) {
        return scope.run(actor, request -> ResponseEntity.ok(switch (operation) {
            case LIST -> publications.list(request, (String) query.get("search"), (String) query.get("state"),
                    (Integer) query.get("limit"), (Integer) query.get("offset"));
            case DETAIL -> publications.detail(request, publicationId);
            case REVISIONS -> publications.revisions(request, publicationId);
            case RESOLVE_PREVIEW -> publications.resolvePreview(request, publicationId);
            case PRECHECK -> publications.precheck(request, publicationId);
            case CLIENTS -> clients.list(request, publicationId);
            case EMPLOYEE_ACCESS -> employeeAccess.get(request, publicationId);
        }));
    }

    @Override
    public ResponseEntity<?> draft(PlatformAuthenticatedSession actor, Draft operation, Long publicationId, Long itemId, Map<String, Object> body) {
        return scope.run(actor, request -> ResponseEntity.ok(switch (operation) {
            case CREATE -> publications.create(request, new McpPublicationController.CreateRequest(
                    (String) body.get("name"), (String) body.get("description")));
            case RENAME -> publications.rename(request, publicationId, new McpPublicationController.UpdateRequest(
                    (String) body.get("name"), (String) body.get("description")));
            case ADD_ITEM -> publications.addItem(request, publicationId, new McpPublicationController.AddItemRequest(
                    (String) body.get("sourceKind"), (String) body.get("sourceRef"), (String) body.get("alias"),
                    (String) body.get("descriptionOverride"), (String) body.get("riskLevelOverride")));
            case REMOVE_ITEM -> publications.removeItem(request, publicationId, itemId);
        }));
    }

    @Override
    public boolean admits(PlatformAuthenticatedSession actor, Change operation, Map<String, Object> body) {
        return switch (operation) {
            case PUBLISH -> holds(actor, McpHubManagementAccess.MANAGE_PUBLICATIONS)
                    && (!Boolean.TRUE.equals(body == null ? null : body.get("irreversibleAcknowledged"))
                        || holds(actor, McpHubManagementAccess.MANAGE_IRREVERSIBLE));
            case SUSPEND, RESUME, ARCHIVE, ROLLBACK, EMPLOYEE_ACCESS -> holds(actor, McpHubManagementAccess.MANAGE_PUBLICATIONS);
            case CLIENT_REVOKE -> holds(actor, McpHubManagementAccess.MANAGE_CREDENTIALS);
            case CLIENT_UPDATE -> holds(actor, McpHubManagementAccess.MANAGE_CREDENTIALS)
                    && (body == null || body.get("roles") == null || holds(actor, McpHubManagementAccess.MANAGE_CREDENTIAL_ROLES));
        };
    }

    @Override
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> change(PlatformAuthenticatedSession actor, Change operation, long publicationId, Long targetId, Map<String, Object> body) {
        return scope.run(actor, request -> ResponseEntity.ok(switch (operation) {
            case PUBLISH -> publications.publish(request, publicationId,
                    new McpPublicationController.PublishRequest(Boolean.TRUE.equals(body.get("irreversibleAcknowledged"))));
            case SUSPEND -> publications.suspend(request, publicationId);
            case RESUME -> publications.resume(request, publicationId);
            case ARCHIVE -> publications.archive(request, publicationId);
            case ROLLBACK -> publications.rollback(request, publicationId, targetId);
            case EMPLOYEE_ACCESS -> employeeAccess.save(request, publicationId, new McpEmployeeAccessController.SaveRequest(
                    body.get("projectId") == null ? null : ((Number) body.get("projectId")).longValue(), (String) body.get("projectCode"),
                    (String) body.get("displayName"), (Boolean) body.get("enabled"), (Boolean) body.get("writeApprovalRequired")));
            case CLIENT_REVOKE -> clients.revoke(request, publicationId, targetId);
            case CLIENT_UPDATE -> clients.update(request, publicationId, targetId, new McpClientController.UpdateClientRequest(
                    (List<String>) body.get("roles"), (List<String>) body.get("toolScope"), (Boolean) body.get("enabled"),
                    body.get("expiresAt") == null ? null : LocalDateTime.parse(String.valueOf(body.get("expiresAt")))));
        }));
    }

    private static boolean holds(PlatformAuthenticatedSession actor, String permission) {
        return actor != null && actor.permissions() != null
                && (actor.permissions().contains("*") || actor.permissions().contains(permission));
    }
}
