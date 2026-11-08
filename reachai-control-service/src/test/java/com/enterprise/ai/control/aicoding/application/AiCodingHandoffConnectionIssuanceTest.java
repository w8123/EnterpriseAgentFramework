package com.enterprise.ai.control.aicoding.application;

import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.HandoffPackageView;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ActivationClient;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ActivationCommand;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskEntity;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskEventEntity;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskEventMapper;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskHandoffEntity;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskHandoffMapper;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskMapper;
import com.enterprise.ai.control.aicoding.security.AiCodingActivationAttemptService;
import com.enterprise.ai.control.aicoding.security.AiCodingSecretDigester;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AiCodingHandoffConnectionIssuanceTest {
    private final AiCodingTaskMapper tasks = mock(AiCodingTaskMapper.class);
    private final AiCodingTaskHandoffMapper handoffs = mock(AiCodingTaskHandoffMapper.class);
    private final AiCodingTaskEventMapper events = mock(AiCodingTaskEventMapper.class);
    private final AiCodingCredentialPolicyService policies = mock(AiCodingCredentialPolicyService.class);
    private final AiCodingSecretDigester digester = mock(AiCodingSecretDigester.class);
    private final AiCodingHandoffPromptFactory prompts = mock(AiCodingHandoffPromptFactory.class);
    private final AiCodingPowerShellBootstrapFactory powershell = mock(AiCodingPowerShellBootstrapFactory.class);
    private final AiCodingTaskProperties properties = mock(AiCodingTaskProperties.class);
    private final AiCodingHandoffApplicationService service = new AiCodingHandoffApplicationService(
            tasks, handoffs, events, properties, policies, digester,
            mock(AiCodingActivationAttemptService.class), prompts, powershell);

    @Test
    void connectionIssuanceRecordsTheSameHandoffWithoutBuildingScripts() {
        AiCodingTaskEntity task = task();
        when(tasks.selectById("T-1")).thenReturn(task);
        when(tasks.updateById(task)).thenReturn(1);
        when(policies.handoffActivationTtl(7L)).thenReturn(Duration.ofHours(1));
        when(digester.generate("rhc_")).thenReturn("rhc-fixture");
        when(digester.digest("rhc-fixture")).thenReturn("fixture-digest");

        var issued = service.issueForConnection("T-1", "builder");

        var stored = ArgumentCaptor.forClass(AiCodingTaskHandoffEntity.class);
        verify(handoffs).insert(stored.capture());
        assertEquals(issued.handoffId(), stored.getValue().getHandoffId());
        assertEquals("rhc-fixture", issued.activationCode());
        assertEquals("fixture-digest", stored.getValue().getActivationCodeHash());
        assertEquals("ISSUED", stored.getValue().getActivationStatus());
        assertNotNull(stored.getValue().getActivationExpiresAt());
        var event = ArgumentCaptor.forClass(AiCodingTaskEventEntity.class);
        verify(events).insert(event.capture());
        assertEquals("HANDOFF_ISSUED", event.getValue().getEventType());
        assertEquals("builder", event.getValue().getActorName());
        verifyNoInteractions(prompts, powershell);
    }

    @Test
    void otherTaskKindsCanStillBuildTheExistingHandoffPackage() {
        AiCodingTaskEntity task = task();
        when(tasks.selectById("T-1")).thenReturn(task);
        when(tasks.updateById(task)).thenReturn(1);
        when(policies.handoffActivationTtl(7L)).thenReturn(Duration.ofHours(1));
        when(digester.generate("rhc_")).thenReturn("rhc-fixture");
        when(digester.digest("rhc-fixture")).thenReturn("fixture-digest");
        HandoffPackageView packageView = mock(HandoffPackageView.class);
        when(prompts.build(eq("https://reachai.test"), eq(task), any(), eq("rhc-fixture")))
                .thenReturn(packageView);

        assertEquals(packageView, service.issue("T-1", "https://reachai.test", "builder"));
        verify(prompts).build(eq("https://reachai.test"), eq(task), any(), eq("rhc-fixture"));
    }

    @Test
    void connectionActivationDoesNotBuildShellSetup() {
        AiCodingTaskEntity task = task();
        AiCodingTaskHandoffEntity handoff = new AiCodingTaskHandoffEntity();
        handoff.setHandoffId("H-1");
        handoff.setTaskId("T-1");
        handoff.setActivationStatus("ISSUED");
        handoff.setActivationExpiresAt(LocalDateTime.now().plusHours(1));
        handoff.setActivationCodeHash("fixture-digest");
        when(handoffs.selectById("H-1")).thenReturn(handoff);
        when(tasks.selectById("T-1")).thenReturn(task);
        when(tasks.updateById(task)).thenReturn(1);
        when(handoffs.update(isNull(), any())).thenReturn(1);
        when(properties.getMaxActivationAttempts()).thenReturn(5);
        when(properties.getConnectionLease()).thenReturn(Duration.ofMinutes(5));
        when(policies.taskTokenTtl(7L)).thenReturn(Duration.ofHours(1));
        when(digester.matches("rhc-fixture", "fixture-digest")).thenReturn(true);
        when(digester.generate("rtt_")).thenReturn("rtt-fixture");
        when(digester.digest("rtt-fixture")).thenReturn("token-digest");
        ActivationCommand command = new ActivationCommand(AiCodingHandoffApplicationService.ACTIVATION_SCHEMA,
                "rhc-fixture", new ActivationClient("CODEX", "mcp-connection:41"));

        var activated = service.activateForConnection("H-1", command, "https://reachai.test");

        assertEquals("rtt-fixture", activated.taskToken());
        assertNull(activated.clientSetup());
        verifyNoInteractions(powershell, prompts);
    }

    private static AiCodingTaskEntity task() {
        AiCodingTaskEntity task = new AiCodingTaskEntity();
        task.setTaskId("T-1");
        task.setProjectId(7L);
        task.setExecutorProvider("CODEX");
        task.setExecutionMode("EXTERNAL_CLIENT");
        task.setExecutionStatus("READY");
        return task;
    }
}
