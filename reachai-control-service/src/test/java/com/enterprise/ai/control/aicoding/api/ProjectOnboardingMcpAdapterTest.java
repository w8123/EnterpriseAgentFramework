package com.enterprise.ai.control.aicoding.api;

import com.enterprise.ai.control.aicoding.application.ProjectOnboardingCreationLock;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ConnectionView;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.CreateTaskCommand;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskTargetView;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskView;
import com.enterprise.ai.control.client.capability.CapabilityProjectOnboardingClient;
import com.enterprise.ai.control.identity.PlatformAiConnectionService.ConnectionIdentity;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformDelegatedRequestScope;
import com.enterprise.ai.control.identity.PlatformPermissions;
import com.enterprise.ai.control.identity.PlatformPrincipal;
import com.enterprise.ai.control.mcp.application.port.McpPlatformAiCodingPort;
import com.enterprise.ai.control.mcp.application.port.McpPlatformProjectAgentProvisioningPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProjectOnboardingMcpAdapterTest {
    private final AiCodingTaskConsoleController console = mock(AiCodingTaskConsoleController.class);
    private final CapabilityProjectOnboardingClient projects = mock(CapabilityProjectOnboardingClient.class);
    private final PlatformDelegatedRequestScope scope = mock(PlatformDelegatedRequestScope.class);
    private final McpPlatformAiCodingPort claims = mock(McpPlatformAiCodingPort.class);
    private final McpPlatformProjectAgentProvisioningPort projectAgent = mock(McpPlatformProjectAgentProvisioningPort.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<McpPlatformAiCodingPort> provider = mock(ObjectProvider.class);
    private final PlatformAuthenticatedSession session = mock(PlatformAuthenticatedSession.class);
    private final ConnectionIdentity actor = new ConnectionIdentity(41L, "CODEX", session);
    private final ObjectMapper json = new ObjectMapper();
    private final ProjectOnboardingMcpAdapter adapter = new ProjectOnboardingMcpAdapter(console, projects, scope, provider,
            json, mock(ProjectOnboardingCreationLock.class), projectAgent);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        when(session.hasResourcePermission(PlatformPermissions.PLATFORM_WRITE, "PROJECT", null, "erp")).thenReturn(true);
        when(session.user()).thenReturn(new PlatformPrincipal(1L, "builder", "Builder"));
        when(projects.getProjectByCode("erp")).thenReturn(Map.of("id", 12L));
        when(provider.getIfAvailable()).thenReturn(claims);
        doReturn(ResponseEntity.ok(Map.of())).when(projectAgent).provision(session, 12L);
        when(scope.run(eq(session), any())).thenAnswer(invocation ->
                ((Function<HttpServletRequest, ResponseEntity<?>>) invocation.getArgument(1)).apply(null));
    }

    private TaskView task(String state, String ref, String language) {
        TaskView task = mock(TaskView.class);
        ConnectionView connection = mock(ConnectionView.class);
        TaskTargetView target = mock(TaskTargetView.class);
        when(task.taskId()).thenReturn("T-1");
        when(task.projectCode()).thenReturn("erp");
        when(task.executionStatus()).thenReturn(state);
        when(task.executorProvider()).thenReturn("CODEX");
        when(task.createdBy()).thenReturn("builder");
        when(task.connection()).thenReturn(connection);
        when(connection.clientSessionRef()).thenReturn(ref);
        when(connection.tokenExpiresAt()).thenReturn(LocalDateTime.now().plusHours(1));
        when(target.snapshot()).thenReturn(json.createObjectNode().put("sdkLanguage", language));
        when(task.targets()).thenReturn(List.of(target));
        return task;
    }

    private void existing(TaskView task) {
        when(console.list(eq(12L), eq("erp"), eq("PROJECT_ONBOARDING"), isNull(), eq(200), isNull()))
                .thenReturn(ResponseEntity.ok(List.of(task)));
    }

    @Test
    void createsWithLanguageAndClaimsForCurrentConnection() {
        when(console.list(eq(12L), eq("erp"), eq("PROJECT_ONBOARDING"), isNull(), eq(200), isNull()))
                .thenReturn(ResponseEntity.ok(List.of()));
        TaskView task = task("READY", null, "java");
        when(console.create(any(), isNull())).thenReturn(ResponseEntity.ok(task));
        doReturn(ResponseEntity.ok(Map.of())).when(claims).claimAsCreator(eq(actor), eq("T-1"));
        assertEquals(200, adapter.start(actor, "erp", "java", "CODEX").getStatusCode().value());
        var command = org.mockito.ArgumentCaptor.forClass(CreateTaskCommand.class);
        verify(console).create(command.capture(), isNull());
        assertEquals("java", command.getValue().targets().get(0).snapshot().path("sdkLanguage").asText());
        verify(claims).claimAsCreator(actor, "T-1");
        verify(projectAgent).provision(session, 12L);
    }

    @Test
    void createsForOtherMcpCodingTools() {
        when(console.list(eq(12L), eq("erp"), eq("PROJECT_ONBOARDING"), isNull(), eq(200), isNull()))
                .thenReturn(ResponseEntity.ok(List.of()));
        TaskView task = task("READY", null, "java");
        when(console.create(any(), isNull())).thenReturn(ResponseEntity.ok(task));
        doReturn(ResponseEntity.ok(Map.of())).when(claims).claimAsCreator(actor, "T-1");
        assertEquals(200, adapter.start(actor, "erp", "java", "OTHER").getStatusCode().value());
        var command = org.mockito.ArgumentCaptor.forClass(CreateTaskCommand.class);
        verify(console).create(command.capture(), isNull());
        assertEquals("OTHER", command.getValue().executorProvider());
    }

    @Test
    void resumesCurrentConnectionWithoutReplacingClaim() {
        existing(task("RUNNING", "mcp-connection:41", "java"));
        assertEquals(200, adapter.start(actor, "erp", "java", "CODEX").getStatusCode().value());
        verifyNoInteractions(claims);
        verifyNoInteractions(projectAgent);
    }

    @Test
    void unknownProvisioningResultStillStartsTaskAndIsReportedWithoutRetry() {
        when(console.list(eq(12L), eq("erp"), eq("PROJECT_ONBOARDING"), isNull(), eq(200), isNull()))
                .thenReturn(ResponseEntity.ok(List.of()));
        TaskView createdTask = task("READY", null, "java");
        when(console.create(any(), isNull())).thenReturn(ResponseEntity.ok(createdTask));
        doReturn(ResponseEntity.ok(Map.of())).when(claims).claimAsCreator(actor, "T-1");
        when(projectAgent.provision(session, 12L)).thenThrow(new IllegalStateException("connection lost"));
        var response = adapter.start(actor, "erp", "java", "CODEX");
        var body = (Map<?, ?>) response.getBody();
        var provisioning = (Map<?, ?>) body.get("projectAgentProvisioning");
        assertEquals("UNKNOWN", provisioning.get("status"));
        verify(projectAgent, times(1)).provision(session, 12L);
        verify(claims).claimAsCreator(actor, "T-1");
    }

    @Test
    void rejectedProvisioningIsReportedWhileTheTaskCanContinue() {
        when(console.list(eq(12L), eq("erp"), eq("PROJECT_ONBOARDING"), isNull(), eq(200), isNull()))
                .thenReturn(ResponseEntity.ok(List.of()));
        TaskView createdTask = task("READY", null, "java");
        when(console.create(any(), isNull())).thenReturn(ResponseEntity.ok(createdTask));
        doReturn(ResponseEntity.ok(Map.of())).when(claims).claimAsCreator(actor, "T-1");
        doReturn(ResponseEntity.status(409).build()).when(projectAgent).provision(session, 12L);
        var body = (Map<?, ?>) adapter.start(actor, "erp", "java", "CODEX").getBody();
        assertEquals("NEEDS_ATTENTION", ((Map<?, ?>) body.get("projectAgentProvisioning")).get("status"));
        verify(claims).claimAsCreator(actor, "T-1");
    }

    @Test
    void otherConnectionsClaimCannotBeTakenOver() {
        existing(task("RUNNING", "mcp-connection:42", "java"));
        when(claims.claimAsCreator(actor, "T-1")).thenReturn(ResponseEntity.status(409).build());
        assertEquals(409, adapter.start(actor, "erp", "java", "CODEX").getStatusCode().value());
    }

    @Test
    void acceptanceReadyReturnsTaskWithoutClaiming() {
        existing(task("ACCEPTANCE_READY", null, "java"));
        assertEquals(200, adapter.start(actor, "erp", "java", "CODEX").getStatusCode().value());
        verifyNoInteractions(claims);
    }

    @Test
    void languageMismatchReturnsConflict() {
        existing(task("RUNNING", "mcp-connection:41", "csharp"));
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> adapter.start(actor, "erp", "java", "CODEX")).getStatusCode().value());
    }
}
