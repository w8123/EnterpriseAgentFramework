package com.enterprise.ai.control.aicoding.application;

import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ActivationCommand;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ActivationView;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskEntity;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskHandoffEntity;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskMapper;
import com.enterprise.ai.control.identity.PlatformAiConnectionService.ConnectionIdentity;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformPrincipal;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiCodingConnectionClaimServiceTest {
    private final AiCodingTaskMapper tasks = mock(AiCodingTaskMapper.class);
    private final AiCodingHandoffApplicationService handoffs = mock(AiCodingHandoffApplicationService.class);
    private final AiCodingConnectionClaimService service = new AiCodingConnectionClaimService(tasks, handoffs);
    private final ConnectionIdentity identity = new ConnectionIdentity(41L, "CLAUDE_CODE", new PlatformAuthenticatedSession(
            new PlatformPrincipal(7L, "builder", "Builder"), "s", LocalDateTime.now().plusHours(1), List.of(), List.of(), List.of()));

    private AiCodingTaskEntity task(String createdBy) {
        AiCodingTaskEntity task = new AiCodingTaskEntity();
        task.setTaskId("T-1");
        task.setExecutorProvider("CODEX");
        task.setExecutionStatus("READY");
        task.setCreatedBy(createdBy);
        when(tasks.selectById("T-1")).thenReturn(task);
        return task;
    }

    @Test
    void claimActivatesTheIssuedHandoffForThisConnectionAndKeepsTheTokenServerSide() {
        task("someone");
        AiCodingTaskHandoffEntity issued = new AiCodingTaskHandoffEntity();
        issued.setHandoffId("h1");
        issued.setActivationStatus("ISSUED");
        when(handoffs.latest("T-1")).thenReturn(issued);
        LocalDateTime lease = LocalDateTime.now().plusMinutes(5);
        when(handoffs.activateForConnection(eq("h1"), any(), eq("https://r"))).thenReturn(
                new ActivationView("s", "T-1", "v1", "rtt_secret", "root", lease.plusDays(1), lease, null));

        var view = service.claim("T-1", " rhc_code ", identity, "https://r");

        ArgumentCaptor<ActivationCommand> command = ArgumentCaptor.forClass(ActivationCommand.class);
        verify(handoffs).activateForConnection(eq("h1"), command.capture(), eq("https://r"));
        assertEquals("rhc_code", command.getValue().activationCode());
        assertEquals("CODEX", command.getValue().client().provider());
        assertEquals("mcp-connection:41", command.getValue().client().sessionRef());
        assertEquals(lease, view.leaseExpiresAt());
    }

    @Test
    void claimWithoutAWaitingCodeIsRefused() {
        task("someone");
        when(handoffs.latest("T-1")).thenReturn(null);
        ResponseStatusException refused = assertThrows(ResponseStatusException.class,
                () -> service.claim("T-1", "rhc_code", identity, "https://r"));
        assertEquals(409, refused.getStatusCode().value());
    }

    @Test
    void onlyTheCreatorMayClaimWithoutACode() {
        task("someone-else");
        ResponseStatusException refused = assertThrows(ResponseStatusException.class,
                () -> service.claimAsCreator("T-1", identity, "https://r"));
        assertEquals(403, refused.getStatusCode().value());
        verify(handoffs, never()).issueForConnection(any(), any());

        task("builder");
        when(handoffs.issueForConnection("T-1", "builder")).thenReturn(
                new AiCodingHandoffApplicationService.ConnectionActivation("h2", "rhc_auto"));
        when(handoffs.activateForConnection(eq("h2"), any(), eq("https://r"))).thenReturn(
                new ActivationView("s", "T-1", "v1", "rtt", "root", LocalDateTime.now(), LocalDateTime.now(), null));
        service.claimAsCreator("T-1", identity, "https://r");
        verify(handoffs).activateForConnection(eq("h2"), any(), eq("https://r"));
    }

    @Test
    void creatorCanRecoverAnExpiredClaimOnTheSameConnection() {
        task("builder");
        AiCodingTaskHandoffEntity expired = new AiCodingTaskHandoffEntity();
        expired.setActivationStatus("ACTIVATED");
        expired.setClientSessionRef("mcp-connection:41");
        expired.setTokenExpiresAt(LocalDateTime.now().minusMinutes(1));
        when(handoffs.latest("T-1")).thenReturn(expired);
        when(handoffs.issueForConnection("T-1", "builder")).thenReturn(
                new AiCodingHandoffApplicationService.ConnectionActivation("h2", "rhc_auto"));
        when(handoffs.activateForConnection(eq("h2"), any(), eq("https://r"))).thenReturn(
                new ActivationView("s", "T-1", "v1", "rtt", "root", LocalDateTime.now(), LocalDateTime.now(), null));
        service.claimAsCreator("T-1", identity, "https://r");
        verify(handoffs).activateForConnection(eq("h2"), any(), eq("https://r"));
    }

    @Test
    void creatorCannotTakeOverAnotherConnectionsExpiredClaim() {
        task("builder");
        AiCodingTaskHandoffEntity expired = new AiCodingTaskHandoffEntity();
        expired.setActivationStatus("ACTIVATED");
        expired.setClientSessionRef("mcp-connection:42");
        expired.setTokenExpiresAt(LocalDateTime.now().minusMinutes(1));
        when(handoffs.latest("T-1")).thenReturn(expired);
        assertThrows(ResponseStatusException.class, () -> service.claimAsCreator("T-1", identity, "https://r"));
        verify(handoffs, never()).issueForConnection(any(), any());
    }

    @Test
    void otherMcpToolCanActivateAnOtherTask() {
        AiCodingTaskEntity task = task("builder");
        task.setExecutorProvider("OTHER");
        when(handoffs.issueForConnection("T-1", "builder")).thenReturn(
                new AiCodingHandoffApplicationService.ConnectionActivation("h2", "rhc_auto"));
        when(handoffs.activateForConnection(eq("h2"), any(), eq("https://r"))).thenReturn(
                new ActivationView("s", "T-1", "v1", "rtt", "root", LocalDateTime.now(), LocalDateTime.now(), null));
        service.claimAsCreator("T-1", identity, "https://r");
        ArgumentCaptor<ActivationCommand> command = ArgumentCaptor.forClass(ActivationCommand.class);
        verify(handoffs).activateForConnection(eq("h2"), command.capture(), eq("https://r"));
        assertEquals("OTHER", command.getValue().client().provider());
        assertEquals("OTHER", com.enterprise.ai.control.aicoding.domain.AiCodingTaskValues.normalizeClientProvider("OTHER", "OTHER", "provider"));
    }
}
