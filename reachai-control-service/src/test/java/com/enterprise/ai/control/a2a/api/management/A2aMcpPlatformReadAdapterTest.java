package com.enterprise.ai.control.a2a.api.management;

import com.enterprise.ai.control.a2a.domain.A2aDomainException;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformDelegatedRequestScope;
import com.enterprise.ai.control.identity.PlatformPrincipal;
import com.enterprise.ai.control.mcp.application.port.McpPlatformA2aReadPort.Read;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class A2aMcpPlatformReadAdapterTest {
    private final A2aTaskManagementController tasks = mock(A2aTaskManagementController.class);
    private final A2aPublicationController publications = mock(A2aPublicationController.class);
    private final PlatformDelegatedRequestScope scope = mock(PlatformDelegatedRequestScope.class);
    private final HttpServletRequest request = mock(HttpServletRequest.class);
    private final A2aMcpPlatformReadAdapter adapter = new A2aMcpPlatformReadAdapter(mock(A2aHubOverviewController.class), publications,
            mock(A2aRemoteAgentController.class), tasks, mock(A2aPrincipalController.class), mock(A2aTrustProfileController.class),
            mock(A2aCredentialController.class), new A2aHubManagementExceptionHandler(), scope);
    private final PlatformAuthenticatedSession actor = new PlatformAuthenticatedSession(new PlatformPrincipal(1L, "u", "U"), "s", null,
            List.of(), List.of("a2a-hub:read"), List.of());

    @SuppressWarnings("unchecked")
    A2aMcpPlatformReadAdapterTest() {
        when(scope.run(eq(actor), any())).thenAnswer(call -> ((Function<HttpServletRequest, ResponseEntity<?>>) call.getArgument(1)).apply(request));
    }

    @Test void payloadReadsGoThroughTheAuditedConsoleEndpoint() {
        doReturn(ResponseEntity.ok(Map.of("payload", "x"))).when(tasks).artifactPayload(request, "task-1", "artifact-2", "INBOUND");
        assertEquals(200, adapter.read(actor, Read.ARTIFACT_PAYLOAD, "task-1", "artifact-2", Map.of("direction", "INBOUND")).getStatusCode().value());
        verify(tasks).artifactPayload(request, "task-1", "artifact-2", "INBOUND");
    }

    @Test void domainErrorsKeepTheConsoleStatus() {
        when(publications.detail(request, 9L)).thenThrow(new A2aDomainException("A2A_PUBLICATION_NOT_FOUND", "missing"));
        assertEquals(404, adapter.read(actor, Read.PUBLICATION, "9", null, Map.of()).getStatusCode().value());
    }
}
