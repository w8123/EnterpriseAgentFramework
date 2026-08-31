package com.enterprise.ai.control.a2a.api.management;

import com.enterprise.ai.control.a2a.application.A2aPageView;
import com.enterprise.ai.control.a2a.application.remoteagent.A2aRemoteAgentApplicationService;
import com.enterprise.ai.control.a2a.application.remoteagent.A2aRemoteAgentContracts.DetailView;
import com.enterprise.ai.control.a2a.application.remoteagent.A2aRemoteAgentContracts.DiscoverRequest;
import com.enterprise.ai.control.a2a.application.remoteagent.A2aRemoteAgentContracts.DiscoveryResult;
import com.enterprise.ai.control.a2a.application.remoteagent.A2aRemoteAgentContracts.RejectRequest;
import com.enterprise.ai.control.a2a.application.remoteagent.A2aRemoteAgentContracts.RemoteAgentView;
import com.enterprise.ai.control.a2a.application.remoteagent.A2aRemoteAgentContracts.ReviewRequest;
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

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/a2a-hub")
@RequiredArgsConstructor
public class A2aRemoteAgentController {

    private final A2aRemoteAgentApplicationService service;
    private final A2aHubManagementAccess access;
    private final PlatformAuthAuditService auditService;

    @GetMapping("/remote-agents")
    public A2aPageView<RemoteAgentView> list(
            HttpServletRequest request,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String health,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset) {
        access.require(request, A2aHubManagementAccess.READ);
        return service.list(search, status, health, limit, offset);
    }

    @GetMapping("/remote-agents/{remoteAgentId}")
    public DetailView detail(HttpServletRequest request, @PathVariable long remoteAgentId) {
        access.require(request, A2aHubManagementAccess.READ);
        return service.detail(remoteAgentId);
    }

    @PostMapping("/remote-agents:discover")
    @ResponseStatus(HttpStatus.CREATED)
    public DiscoveryResult discover(HttpServletRequest request, @RequestBody DiscoverRequest body) {
        PlatformAuthenticatedSession session = access.require(
                request, A2aHubManagementAccess.MANAGE_REMOTE_AGENTS);
        DiscoveryResult result = service.discover(body, access.actor(session));
        auditDiscovery(session, "A2A_REMOTE_AGENT_DISCOVERED", result);
        return result;
    }

    @PostMapping("/remote-agents/{remoteAgentId}:rediscover")
    public DiscoveryResult rediscover(HttpServletRequest request, @PathVariable long remoteAgentId) {
        PlatformAuthenticatedSession session = access.require(
                request, A2aHubManagementAccess.MANAGE_REMOTE_AGENTS);
        DiscoveryResult result = service.rediscover(remoteAgentId, access.actor(session));
        auditDiscovery(session, "A2A_REMOTE_AGENT_REDISCOVERED", result);
        return result;
    }

    @PostMapping("/remote-agents/{remoteAgentId}/revisions/{revisionId}:approve")
    public DetailView approve(
            HttpServletRequest request,
            @PathVariable long remoteAgentId,
            @PathVariable long revisionId,
            @RequestBody ReviewRequest body) {
        PlatformAuthenticatedSession session = access.require(
                request, A2aHubManagementAccess.MANAGE_REMOTE_AGENTS);
        DetailView result = service.approve(
                remoteAgentId, revisionId, body, access.actor(session));
        auditState(session, "A2A_REMOTE_AGENT_REVISION_APPROVED", result,
                Map.of("revisionId", revisionId));
        return result;
    }

    @PostMapping("/remote-agents/{remoteAgentId}/revisions/{revisionId}:reject")
    public DetailView reject(
            HttpServletRequest request,
            @PathVariable long remoteAgentId,
            @PathVariable long revisionId,
            @RequestBody(required = false) RejectRequest body) {
        PlatformAuthenticatedSession session = access.require(
                request, A2aHubManagementAccess.MANAGE_REMOTE_AGENTS);
        DetailView result = service.reject(remoteAgentId, revisionId,
                body == null ? null : body.reason(), access.actor(session));
        auditState(session, "A2A_REMOTE_AGENT_REVISION_REJECTED", result,
                Map.of("revisionId", revisionId));
        return result;
    }

    @PostMapping("/remote-agents/{remoteAgentId}:disable")
    public DetailView disable(HttpServletRequest request, @PathVariable long remoteAgentId) {
        PlatformAuthenticatedSession session = access.require(
                request, A2aHubManagementAccess.MANAGE_REMOTE_AGENTS);
        DetailView result = service.disable(remoteAgentId, access.actor(session));
        auditState(session, "A2A_REMOTE_AGENT_DISABLED", result, Map.of());
        return result;
    }

    @PostMapping("/remote-agents/{remoteAgentId}:enable")
    public DetailView enable(HttpServletRequest request, @PathVariable long remoteAgentId) {
        PlatformAuthenticatedSession session = access.require(
                request, A2aHubManagementAccess.MANAGE_REMOTE_AGENTS);
        DetailView result = service.enable(remoteAgentId, access.actor(session));
        auditState(session, "A2A_REMOTE_AGENT_ENABLED", result, Map.of());
        return result;
    }

    private void auditDiscovery(
            PlatformAuthenticatedSession session, String eventType, DiscoveryResult result) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("remoteAgentKey", result.detail().remoteAgent().remoteAgentKey());
        detail.put("outcome", result.outcome());
        detail.put("reasonCode", result.reasonCode() == null ? "" : result.reasonCode());
        detail.put("status", result.detail().remoteAgent().status());
        auditService.record(session, eventType, "A2A_REMOTE_AGENT",
                result.detail().remoteAgent().id().toString(), detail);
    }

    private void auditState(
            PlatformAuthenticatedSession session,
            String eventType,
            DetailView result,
            Map<String, Object> additional) {
        Map<String, Object> detail = new LinkedHashMap<>(additional);
        detail.put("remoteAgentKey", result.remoteAgent().remoteAgentKey());
        detail.put("status", result.remoteAgent().status());
        detail.put("currentRevisionId", result.remoteAgent().currentRevisionId() == null
                ? "" : result.remoteAgent().currentRevisionId());
        auditService.record(session, eventType, "A2A_REMOTE_AGENT",
                result.remoteAgent().id().toString(), detail);
    }
}
