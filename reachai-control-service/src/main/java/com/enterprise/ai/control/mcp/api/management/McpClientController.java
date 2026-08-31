package com.enterprise.ai.control.mcp.api.management;

import com.enterprise.ai.control.identity.PlatformAuthAuditService;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.mcp.application.identity.McpClientApplicationService;
import com.enterprise.ai.control.mcp.domain.client.McpClient;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/mcp/publications/{publicationId}/clients")
public class McpClientController {

    private final McpClientApplicationService service;
    private final McpHubManagementAccess access;
    private final PlatformAuthAuditService auditService;

    public McpClientController(McpClientApplicationService service,
                               McpHubManagementAccess access,
                               PlatformAuthAuditService auditService) {
        this.service = service;
        this.access = access;
        this.auditService = auditService;
    }

    @GetMapping
    public List<Map<String, Object>> list(HttpServletRequest request,
                                          @PathVariable long publicationId) {
        access.require(request, McpHubManagementAccess.READ);
        return service.list(publicationId).stream().map(McpClientController::view).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(HttpServletRequest request,
                                      @PathVariable long publicationId,
                                      @RequestBody CreateClientRequest body) {
        PlatformAuthenticatedSession session = access.require(
                request, McpHubManagementAccess.MANAGE_CREDENTIALS);
        access.require(request, McpHubManagementAccess.MANAGE_CREDENTIAL_ROLES);
        McpClientApplicationService.CreatedClient created = service.create(
                publicationId, body.name(), body.projectId(), body.projectCode(), body.environment(),
                body.tenantId(), body.roles(), body.toolScope(), body.expiresAt());
        auditService.record(session, "MCP_CLIENT_CREATED", "MCP_CLIENT",
                created.client().id().toString(),
                Map.of("publicationId", publicationId, "name", created.client().name()));
        return keyResponse(created);
    }

    @PostMapping("/{clientId}/rotate")
    public Map<String, Object> rotate(HttpServletRequest request,
                                      @PathVariable long publicationId,
                                      @PathVariable long clientId) {
        PlatformAuthenticatedSession session = access.require(
                request, McpHubManagementAccess.MANAGE_CREDENTIALS);
        McpClientApplicationService.CreatedClient created = service.rotate(publicationId, clientId);
        auditService.record(session, "MCP_CLIENT_ROTATED", "MCP_CLIENT",
                String.valueOf(clientId),
                Map.of("publicationId", publicationId, "replacementId", created.client().id()));
        return keyResponse(created);
    }

    @PostMapping("/{clientId}/revoke")
    public Map<String, Object> revoke(HttpServletRequest request,
                                      @PathVariable long publicationId,
                                      @PathVariable long clientId) {
        PlatformAuthenticatedSession session = access.require(
                request, McpHubManagementAccess.MANAGE_CREDENTIALS);
        McpClient revoked = service.revoke(publicationId, clientId);
        auditService.record(session, "MCP_CLIENT_REVOKED", "MCP_CLIENT",
                String.valueOf(clientId), Map.of("publicationId", publicationId,
                        "state", revoked.state().name()));
        return view(revoked);
    }

    @PutMapping("/{clientId}")
    public Map<String, Object> update(HttpServletRequest request,
                                      @PathVariable long publicationId,
                                      @PathVariable long clientId,
                                      @RequestBody UpdateClientRequest body) {
        PlatformAuthenticatedSession session = access.require(
                request, McpHubManagementAccess.MANAGE_CREDENTIALS);
        if (body.roles() != null) {
            access.require(request, McpHubManagementAccess.MANAGE_CREDENTIAL_ROLES);
        }
        McpClient updated = service.update(publicationId, clientId, body.roles(),
                body.toolScope(), body.enabled(), body.expiresAt());
        auditService.record(session, "MCP_CLIENT_UPDATED", "MCP_CLIENT",
                String.valueOf(clientId), Map.of("publicationId", publicationId));
        return view(updated);
    }

    private Map<String, Object> keyResponse(McpClientApplicationService.CreatedClient created) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("client", view(created.client()));
        response.put("plaintextApiKey", created.plaintextApiKey());
        response.put("note", "Store this API key now. It will not be shown again.");
        return response;
    }

    private static Map<String, Object> view(McpClient client) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", client.id());
        view.put("publicationId", client.publicationId());
        view.put("name", client.name());
        view.put("projectId", client.projectId());
        view.put("projectCode", client.projectCode());
        view.put("environment", client.environment());
        view.put("tenantId", client.tenantId());
        view.put("apiKeyPrefix", client.apiKeyPrefix());
        view.put("roles", client.roles());
        view.put("toolScope", client.toolScope());
        view.put("state", client.state().name());
        view.put("enabled", client.enabled());
        view.put("expiresAt", client.expiresAt());
        view.put("lastUsedAt", client.lastUsedAt());
        view.put("createdAt", client.createdAt());
        return view;
    }

    public record CreateClientRequest(String name, Long projectId, String projectCode,
                                      String environment, String tenantId,
                                      List<String> roles, List<String> toolScope,
                                      LocalDateTime expiresAt) {
    }

    public record UpdateClientRequest(List<String> roles, List<String> toolScope,
                                      Boolean enabled, LocalDateTime expiresAt) {
    }
}
