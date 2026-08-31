package com.enterprise.ai.control.a2a.api.management;

import com.enterprise.ai.control.a2a.application.A2aPageView;
import com.enterprise.ai.control.a2a.application.publication.A2aPublicationApplicationService;
import com.enterprise.ai.control.a2a.application.publication.A2aPublicationContracts.CreateRequest;
import com.enterprise.ai.control.a2a.application.publication.A2aPublicationContracts.DetailView;
import com.enterprise.ai.control.a2a.application.publication.A2aPublicationContracts.PublicationView;
import com.enterprise.ai.control.a2a.application.publication.A2aPublicationContracts.RevisionDraftRequest;
import com.enterprise.ai.control.a2a.application.publication.A2aPublicationContracts.RevisionView;
import com.enterprise.ai.control.identity.PlatformAuthAuditService;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/a2a-hub/publications")
@RequiredArgsConstructor
public class A2aPublicationController {

    private final A2aPublicationApplicationService service;
    private final A2aHubManagementAccess access;
    private final PlatformAuthAuditService auditService;

    @GetMapping
    public A2aPageView<PublicationView> list(
            HttpServletRequest request,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset) {
        access.require(request, A2aHubManagementAccess.READ);
        return service.list(search, status, limit, offset);
    }

    @GetMapping("/{publicationId}")
    public DetailView detail(HttpServletRequest request, @PathVariable long publicationId) {
        access.require(request, A2aHubManagementAccess.READ);
        return service.detail(publicationId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DetailView create(HttpServletRequest request, @RequestBody CreateRequest body) {
        PlatformAuthenticatedSession session = access.require(
                request, A2aHubManagementAccess.MANAGE_PUBLICATIONS);
        DetailView result = service.create(body, access.actor(session));
        audit(session, "A2A_PUBLICATION_CREATED", result);
        return result;
    }

    @PostMapping("/{publicationId}/revisions")
    @ResponseStatus(HttpStatus.CREATED)
    public RevisionView createRevision(
            HttpServletRequest request,
            @PathVariable long publicationId,
            @RequestBody RevisionDraftRequest body) {
        PlatformAuthenticatedSession session = access.require(
                request, A2aHubManagementAccess.MANAGE_PUBLICATIONS);
        RevisionView result = service.createRevision(publicationId, body, access.actor(session));
        auditService.record(session, "A2A_PUBLICATION_REVISION_CREATED", "A2A_PUBLICATION_REVISION",
                result.id().toString(), Map.of("publicationId", publicationId,
                        "revisionNo", result.revisionNo(), "cardSha256", result.agentCardSha256()));
        return result;
    }

    @PostMapping("/{publicationId}/revisions/{revisionId}:preflight")
    public DetailView preflight(
            HttpServletRequest request,
            @PathVariable long publicationId,
            @PathVariable long revisionId) {
        PlatformAuthenticatedSession session = access.require(
                request, A2aHubManagementAccess.MANAGE_PUBLICATIONS);
        DetailView result = service.preflight(publicationId, revisionId, access.actor(session));
        audit(session, "A2A_PUBLICATION_PREFLIGHT_PASSED", result);
        return result;
    }

    @PostMapping("/{publicationId}/revisions/{revisionId}:publish")
    public DetailView publish(
            HttpServletRequest request,
            @PathVariable long publicationId,
            @PathVariable long revisionId) {
        PlatformAuthenticatedSession session = access.require(
                request, A2aHubManagementAccess.MANAGE_PUBLICATIONS);
        DetailView result = service.publish(publicationId, revisionId, access.actor(session));
        audit(session, "A2A_PUBLICATION_PUBLISHED", result);
        return result;
    }

    @PostMapping("/{publicationId}:suspend")
    public DetailView suspend(HttpServletRequest request, @PathVariable long publicationId) {
        PlatformAuthenticatedSession session = access.require(
                request, A2aHubManagementAccess.MANAGE_PUBLICATIONS);
        DetailView result = service.suspend(publicationId, access.actor(session));
        audit(session, "A2A_PUBLICATION_SUSPENDED", result);
        return result;
    }

    @PostMapping("/{publicationId}:resume")
    public DetailView resume(HttpServletRequest request, @PathVariable long publicationId) {
        PlatformAuthenticatedSession session = access.require(
                request, A2aHubManagementAccess.MANAGE_PUBLICATIONS);
        DetailView result = service.resume(publicationId, access.actor(session));
        audit(session, "A2A_PUBLICATION_RESUMED", result);
        return result;
    }

    private void audit(PlatformAuthenticatedSession session, String eventType, DetailView result) {
        PublicationView publication = result.publication();
        auditService.record(session, eventType, "A2A_PUBLICATION", publication.id().toString(),
                Map.of("publicationKey", publication.publicationKey(),
                        "status", publication.status(),
                        "currentRevisionId", publication.currentRevisionId() == null
                                ? "" : publication.currentRevisionId()));
    }
}
