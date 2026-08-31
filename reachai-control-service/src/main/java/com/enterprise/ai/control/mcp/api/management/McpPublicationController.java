package com.enterprise.ai.control.mcp.api.management;

import com.enterprise.ai.control.identity.PlatformAuthAuditService;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.mcp.application.publication.McpPublicationApplicationService;
import com.enterprise.ai.control.mcp.application.publication.McpPublicationApplicationService.DetailView;
import com.enterprise.ai.control.mcp.application.publication.McpPublicationApplicationService.ItemView;
import com.enterprise.ai.control.mcp.application.publication.McpPublicationApplicationService.PublicationView;
import com.enterprise.ai.control.mcp.application.publication.McpPublicationApplicationService.RevisionView;
import com.enterprise.ai.control.mcp.application.port.McpPublicationRepository;
import com.enterprise.ai.control.mcp.domain.publication.McpPublication;
import com.enterprise.ai.control.mcp.domain.publication.McpPublicationItem;
import com.enterprise.ai.control.mcp.domain.publication.McpPublicationItemKind;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/mcp/publications")
public class McpPublicationController {

    private final McpPublicationApplicationService service;
    private final McpHubManagementAccess access;
    private final PlatformAuthAuditService auditService;

    public McpPublicationController(McpPublicationApplicationService service,
                                    McpHubManagementAccess access,
                                    PlatformAuthAuditService auditService) {
        this.service = service;
        this.access = access;
        this.auditService = auditService;
    }

    @GetMapping
    public McpPublicationRepository.Page list(
            HttpServletRequest request,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset) {
        access.require(request, McpHubManagementAccess.READ);
        return service.list(search, state, limit, offset);
    }

    @GetMapping("/{publicationId}")
    public DetailView detail(HttpServletRequest request, @PathVariable long publicationId) {
        access.require(request, McpHubManagementAccess.READ);
        return service.detail(publicationId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PublicationView create(HttpServletRequest request, @RequestBody CreateRequest body) {
        PlatformAuthenticatedSession session = access.require(
                request, McpHubManagementAccess.MANAGE_PUBLICATIONS);
        McpPublication created = service.create(body.name(), body.description());
        auditService.record(session, "MCP_PUBLICATION_CREATED", "MCP_PUBLICATION",
                created.id().toString(), Map.of("name", created.name()));
        return view(created);
    }

    @PutMapping("/{publicationId}")
    public PublicationView rename(HttpServletRequest request,
                                   @PathVariable long publicationId,
                                   @RequestBody UpdateRequest body) {
        PlatformAuthenticatedSession session = access.require(
                request, McpHubManagementAccess.MANAGE_PUBLICATIONS);
        McpPublication renamed = service.rename(publicationId, body.name(), body.description());
        auditService.record(session, "MCP_PUBLICATION_RENAMED", "MCP_PUBLICATION",
                String.valueOf(publicationId), Map.of("name", renamed.name()));
        return view(renamed);
    }

    @PostMapping("/{publicationId}/items")
    @ResponseStatus(HttpStatus.CREATED)
    public ItemView addItem(HttpServletRequest request,
                            @PathVariable long publicationId,
                            @RequestBody AddItemRequest body) {
        PlatformAuthenticatedSession session = access.require(
                request, McpHubManagementAccess.MANAGE_PUBLICATIONS);
        McpPublicationItem item = service.addItem(
                publicationId,
                McpPublicationItemKind.parse(body.sourceKind()),
                body.sourceRef(), body.alias(), body.descriptionOverride(), body.riskLevelOverride());
        auditService.record(session, "MCP_PUBLICATION_ITEM_ADDED", "MCP_PUBLICATION_ITEM",
                item.id().toString(), Map.of("publicationId", publicationId,
                        "sourceKind", item.sourceKind().name(), "sourceRef", item.sourceRef()));
        return itemView(item);
    }

    @DeleteMapping("/{publicationId}/items/{itemId}")
    public Map<String, Object> removeItem(HttpServletRequest request,
                                          @PathVariable long publicationId,
                                          @PathVariable long itemId) {
        PlatformAuthenticatedSession session = access.require(
                request, McpHubManagementAccess.MANAGE_PUBLICATIONS);
        service.removeItem(publicationId, itemId);
        auditService.record(session, "MCP_PUBLICATION_ITEM_REMOVED", "MCP_PUBLICATION_ITEM",
                String.valueOf(itemId), Map.of("publicationId", publicationId));
        return Map.of("ok", true);
    }

    @PostMapping("/{publicationId}/resolve-preview")
    public List<Map<String, Object>> resolvePreview(HttpServletRequest request,
                                                    @PathVariable long publicationId) {
        access.require(request, McpHubManagementAccess.READ);
        return service.resolvePreview(publicationId);
    }

    @PostMapping("/{publicationId}/precheck")
    public Map<String, Object> precheck(HttpServletRequest request, @PathVariable long publicationId) {
        access.require(request, McpHubManagementAccess.READ);
        return service.precheck(publicationId);
    }

    @PostMapping("/{publicationId}/publish")
    public DetailView publish(HttpServletRequest request,
                              @PathVariable long publicationId,
                              @RequestBody(required = false) PublishRequest body) {
        PlatformAuthenticatedSession session = access.require(
                request, McpHubManagementAccess.MANAGE_PUBLICATIONS);
        boolean acknowledged = body != null && Boolean.TRUE.equals(body.irreversibleAcknowledged());
        if (acknowledged) {
            access.require(request, McpHubManagementAccess.MANAGE_IRREVERSIBLE);
        }
        DetailView result = service.publish(publicationId, acknowledged);
        auditService.record(session, "MCP_PUBLICATION_PUBLISHED", "MCP_PUBLICATION",
                String.valueOf(publicationId), Map.of(
                        "revisionId", result.publication().currentRevisionId() == null
                                ? "" : result.publication().currentRevisionId(),
                        "state", result.publication().state(),
                        "irreversibleAcknowledged", acknowledged));
        return result;
    }

    @PostMapping("/{publicationId}/suspend")
    public DetailView suspend(HttpServletRequest request, @PathVariable long publicationId) {
        PlatformAuthenticatedSession session = access.require(
                request, McpHubManagementAccess.MANAGE_PUBLICATIONS);
        DetailView result = service.suspend(publicationId);
        auditService.record(session, "MCP_PUBLICATION_SUSPENDED", "MCP_PUBLICATION",
                String.valueOf(publicationId), Map.of("state", result.publication().state()));
        return result;
    }

    @PostMapping("/{publicationId}/resume")
    public DetailView resume(HttpServletRequest request, @PathVariable long publicationId) {
        PlatformAuthenticatedSession session = access.require(
                request, McpHubManagementAccess.MANAGE_PUBLICATIONS);
        DetailView result = service.resume(publicationId);
        auditService.record(session, "MCP_PUBLICATION_RESUMED", "MCP_PUBLICATION",
                String.valueOf(publicationId), Map.of("state", result.publication().state()));
        return result;
    }

    @PostMapping("/{publicationId}/archive")
    public DetailView archive(HttpServletRequest request, @PathVariable long publicationId) {
        PlatformAuthenticatedSession session = access.require(
                request, McpHubManagementAccess.MANAGE_PUBLICATIONS);
        DetailView result = service.archive(publicationId);
        auditService.record(session, "MCP_PUBLICATION_ARCHIVED", "MCP_PUBLICATION",
                String.valueOf(publicationId), Map.of("state", result.publication().state()));
        return result;
    }

    @PostMapping("/{publicationId}/revisions/{revisionId}/rollback")
    public DetailView rollback(HttpServletRequest request,
                               @PathVariable long publicationId,
                               @PathVariable long revisionId) {
        PlatformAuthenticatedSession session = access.require(
                request, McpHubManagementAccess.MANAGE_PUBLICATIONS);
        DetailView result = service.rollback(publicationId, revisionId);
        auditService.record(session, "MCP_PUBLICATION_ROLLED_BACK", "MCP_PUBLICATION",
                String.valueOf(publicationId), Map.of("revisionId", revisionId));
        return result;
    }

    @GetMapping("/{publicationId}/revisions")
    public List<RevisionView> revisions(HttpServletRequest request, @PathVariable long publicationId) {
        access.require(request, McpHubManagementAccess.READ);
        return service.revisions(publicationId);
    }

    private PublicationView view(McpPublication publication) {
        return new PublicationView(
                publication.id(), publication.name(), publication.description(),
                publication.state().name(), publication.currentRevisionId(),
                publication.createdAt(), publication.updatedAt());
    }

    private ItemView itemView(McpPublicationItem item) {
        return new ItemView(
                item.id(), item.publicationId(), item.sourceKind().name(), item.sourceRef(),
                item.alias(), item.descriptionOverride(), item.riskLevelOverride(), item.enabled(),
                item.createdAt(), item.updatedAt());
    }

    public record CreateRequest(String name, String description) {
    }

    public record UpdateRequest(String name, String description) {
    }

    public record AddItemRequest(String sourceKind, String sourceRef, String alias,
                                 String descriptionOverride, String riskLevelOverride) {
    }

    public record PublishRequest(Boolean irreversibleAcknowledged) {
    }
}
