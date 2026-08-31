package com.enterprise.ai.control.a2a.api.management;

import com.enterprise.ai.control.a2a.application.A2aPageView;
import com.enterprise.ai.control.a2a.application.identity.A2aCredentialApplicationService;
import com.enterprise.ai.control.a2a.application.identity.A2aCredentialContracts.ApiKeyIssueView;
import com.enterprise.ai.control.a2a.application.identity.A2aCredentialContracts.CreateApiKeyRequest;
import com.enterprise.ai.control.a2a.application.identity.A2aCredentialContracts.CredentialView;
import com.enterprise.ai.control.a2a.application.identity.A2aCredentialContracts.OutboundSecretStoredView;
import com.enterprise.ai.control.a2a.application.identity.A2aCredentialContracts.RotateOutboundSecretRequest;
import com.enterprise.ai.control.a2a.application.identity.A2aCredentialContracts.StoreOutboundSecretRequest;
import com.enterprise.ai.control.identity.PlatformAuthAuditService;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/a2a-hub/credentials")
@RequiredArgsConstructor
public class A2aCredentialController {

    private final A2aCredentialApplicationService service;
    private final A2aHubManagementAccess access;
    private final PlatformAuthAuditService auditService;

    @GetMapping
    public A2aPageView<CredentialView> list(
            HttpServletRequest request,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset) {
        access.require(request, A2aHubManagementAccess.READ);
        return service.list(search, status, limit, offset);
    }

    @GetMapping("/{id}")
    public CredentialView detail(HttpServletRequest request, @PathVariable long id) {
        access.require(request, A2aHubManagementAccess.READ);
        return service.detail(id);
    }

    @PostMapping("/api-keys")
    public ResponseEntity<ApiKeyIssueView> createApiKey(
            HttpServletRequest request, @RequestBody CreateApiKeyRequest body) {
        PlatformAuthenticatedSession session = access.require(
                request, A2aHubManagementAccess.MANAGE_CREDENTIALS);
        ApiKeyIssueView result = service.createApiKey(body, access.actor(session));
        auditIssued(session, "A2A_API_KEY_CREATED", result);
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(result);
    }

    @PostMapping("/{id}:rotate")
    public ResponseEntity<ApiKeyIssueView> rotate(HttpServletRequest request, @PathVariable long id) {
        PlatformAuthenticatedSession session = access.require(
                request, A2aHubManagementAccess.MANAGE_CREDENTIALS);
        ApiKeyIssueView result = service.rotate(id, access.actor(session));
        auditIssued(session, "A2A_API_KEY_ROTATED", result);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }

    @PostMapping("/{id}:revoke")
    public CredentialView revoke(HttpServletRequest request, @PathVariable long id) {
        PlatformAuthenticatedSession session = access.require(
                request, A2aHubManagementAccess.MANAGE_CREDENTIALS);
        CredentialView result = service.revoke(id);
        auditService.record(session, "A2A_CREDENTIAL_REVOKED", "A2A_CREDENTIAL",
                result.id().toString(), Map.of("credentialKey", result.credentialKey(),
                        "versionNo", result.versionNo(), "fingerprint", result.fingerprint()));
        return result;
    }

    @PostMapping("/outbound-secrets")
    public ResponseEntity<OutboundSecretStoredView> storeOutboundSecret(
            HttpServletRequest request, @RequestBody StoreOutboundSecretRequest body) {
        PlatformAuthenticatedSession session = access.require(
                request, A2aHubManagementAccess.MANAGE_CREDENTIALS);
        OutboundSecretStoredView result = service.storeOutboundSecret(body, access.actor(session));
        auditStored(session, "A2A_OUTBOUND_SECRET_STORED", result);
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(result);
    }

    @PostMapping("/{id}:rotate-outbound")
    public ResponseEntity<OutboundSecretStoredView> rotateOutbound(
            HttpServletRequest request,
            @PathVariable long id,
            @RequestBody RotateOutboundSecretRequest body) {
        PlatformAuthenticatedSession session = access.require(
                request, A2aHubManagementAccess.MANAGE_CREDENTIALS);
        OutboundSecretStoredView result = service.rotateOutbound(id, body, access.actor(session));
        auditStored(session, "A2A_OUTBOUND_SECRET_ROTATED", result);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }

    private void auditIssued(PlatformAuthenticatedSession session, String eventType, ApiKeyIssueView result) {
        CredentialView credential = result.credential();
        auditService.record(session, eventType, "A2A_CREDENTIAL", credential.id().toString(),
                Map.of("credentialKey", credential.credentialKey(), "versionNo", credential.versionNo(),
                        "fingerprint", credential.fingerprint(), "expiresAt", credential.expiresAt().toString()));
    }

    private void auditStored(
            PlatformAuthenticatedSession session,
            String eventType,
            OutboundSecretStoredView result) {
        CredentialView credential = result.credential();
        auditService.record(session, eventType, "A2A_CREDENTIAL", credential.id().toString(),
                Map.of("credentialKey", credential.credentialKey(),
                        "direction", credential.direction(),
                        "credentialType", credential.credentialType(),
                        "versionNo", credential.versionNo(),
                        "fingerprint", credential.fingerprint(),
                        "expiresAt", credential.expiresAt().toString()));
    }
}
