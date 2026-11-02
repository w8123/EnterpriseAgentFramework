package com.enterprise.ai.control.a2a.api.management;

import com.enterprise.ai.control.a2a.domain.A2aDomainException;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformDelegatedRequestScope;
import com.enterprise.ai.control.mcp.application.port.McpPlatformA2aReadPort;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Runs platform AI A2A Hub reads through the controllers a person uses in the A2A Hub console. */
@Component
@RequiredArgsConstructor
class A2aMcpPlatformReadAdapter implements McpPlatformA2aReadPort {
    private final A2aHubOverviewController overview;
    private final A2aPublicationController publications;
    private final A2aRemoteAgentController remoteAgents;
    private final A2aTaskManagementController tasks;
    private final A2aPrincipalController principals;
    private final A2aTrustProfileController trustProfiles;
    private final A2aCredentialController credentials;
    private final A2aHubManagementExceptionHandler errors;
    private final PlatformDelegatedRequestScope scope;

    @Override
    public ResponseEntity<?> read(PlatformAuthenticatedSession actor, Read read, String id, String secondId, Map<String, Object> query) {
        String search = (String) query.get("search");
        String status = (String) query.get("status");
        Integer limit = (Integer) query.get("limit");
        Integer offset = (Integer) query.get("offset");
        return scope.run(actor, request -> {
            try {
                return switch (read) {
                    case OVERVIEW -> overview.overview(request);
                    case PUBLICATIONS -> ResponseEntity.ok(publications.list(request, search, status, limit, offset));
                    case PUBLICATION -> ResponseEntity.ok(publications.detail(request, Long.parseLong(id)));
                    case REMOTE_AGENTS -> ResponseEntity.ok(remoteAgents.list(request, search, status, (String) query.get("health"), limit, offset));
                    case REMOTE_AGENT -> ResponseEntity.ok(remoteAgents.detail(request, Long.parseLong(id)));
                    case TASKS -> ResponseEntity.ok(tasks.list(request, search, (String) query.get("direction"), (String) query.get("state"),
                            (Long) query.get("publicationId"), (Long) query.get("remoteAgentId"), null, null, null, null, limit, offset));
                    case TASK -> ResponseEntity.ok(tasks.detail(request, id, (String) query.get("direction")));
                    case MESSAGE_PAYLOAD -> tasks.messagePayload(request, id, secondId, (String) query.get("direction"));
                    case ARTIFACT_PAYLOAD -> tasks.artifactPayload(request, id, secondId, (String) query.get("direction"));
                    case PRINCIPALS -> ResponseEntity.ok(principals.list(request, search, status, limit, offset));
                    case PRINCIPAL -> ResponseEntity.ok(principals.detail(request, Long.parseLong(id)));
                    case TRUST_PROFILES -> ResponseEntity.ok(trustProfiles.list(request, search, status, limit, offset));
                    case TRUST_PROFILE -> ResponseEntity.ok(trustProfiles.detail(request, Long.parseLong(id)));
                    case CREDENTIALS -> ResponseEntity.ok(credentials.list(request, search, status, limit, offset));
                    case CREDENTIAL -> ResponseEntity.ok(credentials.detail(request, Long.parseLong(id)));
                };
            } catch (A2aDomainException rejected) {
                return errors.handle(rejected);
            }
        });
    }
}
