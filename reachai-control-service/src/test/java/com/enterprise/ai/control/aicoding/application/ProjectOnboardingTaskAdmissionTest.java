package com.enterprise.ai.control.aicoding.application;

import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.CreateTaskCommand;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskMapper;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskTargetMapper;
import com.enterprise.ai.control.aicoding.provider.AiCodingTaskProviderRegistry;
import com.enterprise.ai.control.aicoding.provider.AiCodingTaskKindProvider;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskContract;
import com.enterprise.ai.control.aicoding.security.AiCodingSensitiveJsonSanitizer;
import com.enterprise.ai.control.client.capability.CapabilityProjectOnboardingClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProjectOnboardingTaskAdmissionTest {
    @Test
    void existingUnfinishedOnboardingBlocksAnotherTaskBeforeCreation() {
        AiCodingTaskMapper tasks = mock(AiCodingTaskMapper.class);
        ProjectOnboardingCreationLock lock = mock(ProjectOnboardingCreationLock.class);
        when(tasks.selectCount(any())).thenReturn(1L);
        AiCodingTaskCreationService service = new AiCodingTaskCreationService(tasks, mock(AiCodingTaskTargetMapper.class),
                mock(AiCodingTaskProviderRegistry.class), mock(CapabilityProjectOnboardingClient.class),
                mock(AiCodingSensitiveJsonSanitizer.class), new ObjectMapper(), mock(AiCodingTaskJsonSupport.class),
                mock(AiCodingTaskStateChanges.class), lock);
        CreateTaskCommand command = new CreateTaskCommand(12L, "erp", "PROJECT_ONBOARDING", "CODEX",
                "接入", "接入", "user", java.util.List.of());
        ResponseStatusException failure = assertThrows(ResponseStatusException.class, () -> service.create(command));
        assertEquals(409, failure.getStatusCode().value());
        verify(lock).acquire("erp");
    }

    @Test
    void otherProviderCannotStartManagedSandbox() {
        AiCodingTaskProviderRegistry providers = mock(AiCodingTaskProviderRegistry.class);
        AiCodingTaskKindProvider provider = mock(AiCodingTaskKindProvider.class);
        TaskContract contract = mock(TaskContract.class);
        when(providers.require("TEST")).thenReturn(provider);
        when(provider.contract()).thenReturn(contract);
        when(contract.capabilityKey()).thenReturn("TEST");
        when(contract.accessMode()).thenReturn("READ_WRITE");
        AiCodingTaskCreationService service = new AiCodingTaskCreationService(mock(AiCodingTaskMapper.class),
                mock(AiCodingTaskTargetMapper.class), providers, mock(CapabilityProjectOnboardingClient.class),
                mock(AiCodingSensitiveJsonSanitizer.class), new ObjectMapper(), mock(AiCodingTaskJsonSupport.class),
                mock(AiCodingTaskStateChanges.class), mock(ProjectOnboardingCreationLock.class));
        CreateTaskCommand command = new CreateTaskCommand(12L, "erp", "TEST", "OTHER",
                "接入", "接入", "user", java.util.List.of(), "MANAGED_SANDBOX", null);
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> service.create(command));
        org.junit.jupiter.api.Assertions.assertTrue(failure.getMessage().contains("requires executorProvider CODEX"));
    }
}
