package com.enterprise.ai.control.a2a.api.management;

import com.enterprise.ai.control.a2a.application.A2aPageView;
import com.enterprise.ai.control.a2a.application.identity.A2aPrincipalApplicationService;
import com.enterprise.ai.control.a2a.application.identity.A2aPrincipalContracts.CreateRequest;
import com.enterprise.ai.control.a2a.application.identity.A2aPrincipalContracts.StatusRequest;
import com.enterprise.ai.control.a2a.application.identity.A2aPrincipalContracts.View;
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
@RequestMapping("/api/a2a-hub/principals")
@RequiredArgsConstructor
public class A2aPrincipalController {

    private final A2aPrincipalApplicationService service;
    private final A2aHubManagementAccess access;
    private final PlatformAuthAuditService auditService;

    @GetMapping
    public A2aPageView<View> list(
            HttpServletRequest request,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset) {
        access.require(request, A2aHubManagementAccess.READ);
        return service.list(search, status, limit, offset);
    }

    @GetMapping("/{id}")
    public View detail(HttpServletRequest request, @PathVariable long id) {
        access.require(request, A2aHubManagementAccess.READ);
        return service.detail(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public View create(HttpServletRequest request, @RequestBody CreateRequest body) {
        PlatformAuthenticatedSession session = access.require(request, A2aHubManagementAccess.MANAGE_TRUST);
        View result = service.create(body, access.actor(session));
        audit(session, "A2A_PRINCIPAL_CREATED", result);
        return result;
    }

    @PostMapping("/{id}:status")
    public View transition(HttpServletRequest request, @PathVariable long id, @RequestBody StatusRequest body) {
        PlatformAuthenticatedSession session = access.require(request, A2aHubManagementAccess.MANAGE_TRUST);
        View result = service.transition(id, body == null ? null : body.status(), access.actor(session));
        audit(session, "A2A_PRINCIPAL_STATUS_CHANGED", result);
        return result;
    }

    private void audit(PlatformAuthenticatedSession session, String eventType, View result) {
        auditService.record(session, eventType, "A2A_PRINCIPAL", result.id().toString(),
                Map.of("principalKey", result.principalKey(), "status", result.status(),
                        "trustProfileId", result.trustProfileId()));
    }
}
