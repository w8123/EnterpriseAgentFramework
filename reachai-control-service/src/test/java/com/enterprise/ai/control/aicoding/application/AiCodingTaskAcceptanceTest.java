package com.enterprise.ai.control.aicoding.application;

import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ReadinessItem;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskArtifactMapper;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskEntity;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskEventEntity;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskEventMapper;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskMapper;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskQuestionEntity;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskQuestionMapper;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskTargetMapper;
import com.enterprise.ai.control.aicoding.provider.AiCodingContractResourceLoader;
import com.enterprise.ai.control.aicoding.provider.AiCodingTaskKindProvider;
import com.enterprise.ai.control.aicoding.provider.AiCodingTaskProviderRegistry;
import com.enterprise.ai.control.aicoding.security.AiCodingSensitiveJsonSanitizer;
import com.enterprise.ai.control.client.capability.CapabilityProjectOnboardingClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiCodingTaskAcceptanceTest {
    private final ObjectMapper json = new ObjectMapper();
    private final AiCodingTaskMapper tasks = mock(AiCodingTaskMapper.class);
    private final AiCodingTaskTargetMapper targets = mock(AiCodingTaskTargetMapper.class);
    private final AiCodingTaskEventMapper events = mock(AiCodingTaskEventMapper.class);
    private final AiCodingTaskQuestionMapper questions = mock(AiCodingTaskQuestionMapper.class);
    private final AiCodingTaskArtifactMapper artifacts = mock(AiCodingTaskArtifactMapper.class);
    private final AiCodingTaskProviderRegistry providers = mock(AiCodingTaskProviderRegistry.class);
    private final AiCodingTaskKindProvider provider = mock(AiCodingTaskKindProvider.class);
    private final AiCodingHandoffApplicationService handoffs = mock(AiCodingHandoffApplicationService.class);
    private final AiCodingTaskEntity task = new AiCodingTaskEntity();
    private final AiCodingTaskApplicationService service = AiCodingTaskTestServices.create(
            tasks, targets, events, questions, artifacts, providers, mock(AiCodingArtifactProviderExecutor.class),
            handoffs, mock(CapabilityProjectOnboardingClient.class), new AiCodingSensitiveJsonSanitizer(),
            json, new AiCodingContractResourceLoader(json));

    @BeforeEach
    void appliedTaskWithServerObservedGate() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), "acceptance-test"),
                AiCodingTaskQuestionEntity.class);
        task.setTaskId("task-1");
        task.setTaskKind("PAGE_WORKBENCH_WORKFLOW");
        task.setExecutionStatus("RESULT_APPLIED");
        task.setExecutorProvider("CODEX");
        task.setContextSnapshotJson("{}");
        when(tasks.selectById("task-1")).thenReturn(task);
        when(tasks.updateById(any(AiCodingTaskEntity.class))).thenReturn(1);
        when(providers.require(task.getTaskKind())).thenReturn(provider);
        when(provider.acceptanceReadinessKeys()).thenReturn(List.of("E2E_READY"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aPassCannotHideAnotherFailedObservationOfTheSameGate(boolean failFirst) {
        var failed = item(" e2e_ready ", "FAIL");
        var passed = item("E2E_READY", "PASS");
        when(provider.readiness(any(), any())).thenReturn(failFirst ? List.of(failed, passed) : List.of(passed, failed));
        var verified = service.verifyAcceptanceReadiness("task-1");
        assertFalse(verified.acceptanceReady());
        assertEquals("RESULT_APPLIED", verified.task().executionStatus());
        assertEquals(2, verified.readiness().size());
        assertEquals(List.of("End-to-end（FAIL）"), verified.blockers());
        verify(handoffs, never()).closeTaskHandoffs(anyString(), anyString());
    }

    @Test
    void everyMatchingObservationMustPassWhileUnrequiredWarningsDoNotBlock() {
        when(provider.acceptanceReadinessKeys()).thenReturn(List.of(" e2e_ready ", "E2E_READY"));
        var evidence = json.createObjectNode().put("token", "test-placeholder").put("traceId", "trace-1");
        when(provider.readiness(any(), any())).thenReturn(List.of(
                new ReadinessItem("E2E_READY", "End-to-end", " pass ", "verified", evidence),
                item("E2E_READY", "PASS"), item("OPTIONAL", "WARN")));
        var verified = service.verifyAcceptanceReadiness("task-1");
        assertTrue(verified.acceptanceReady());
        assertEquals("ACCEPTANCE_READY", verified.task().executionStatus());
        assertNotEquals("test-placeholder", verified.readiness().get(0).evidence().path("token").asText());
        assertEquals("test-placeholder", evidence.path("token").asText());
        verify(handoffs).closeTaskHandoffs("task-1", "TASK_ACCEPTANCE_READY");
    }

    @Test
    void repeatedBlockedVerificationDoesNotDuplicateItsAuditEvent() {
        when(provider.readiness(any(), any())).thenReturn(List.of(item("E2E_READY", "PENDING")));
        assertFalse(service.verifyAcceptanceReadiness("task-1").acceptanceReady());
        assertFalse(service.verifyAcceptanceReadiness("task-1").acceptanceReady());
        var event = ArgumentCaptor.forClass(AiCodingTaskEventEntity.class);
        verify(events).insert(event.capture());
        assertEquals("ACCEPTANCE_BLOCKED", event.getValue().getEventType());
        verify(tasks).updateById(task);
    }

    @Test
    void acceptanceRechecksCurrentEvidenceBeforeCompleting() {
        task.setExecutionStatus("ACCEPTANCE_READY");
        when(provider.readiness(any(), any())).thenReturn(List.of(item("E2E_READY", "PASS")), List.of());
        assertTrue(service.verifyAcceptanceReadiness("task-1").acceptanceReady());
        assertThrows(IllegalStateException.class,
                () -> service.finishAcceptance("task-1", true, "Accepted", "reviewer"));
        assertEquals("ACCEPTANCE_READY", task.getExecutionStatus());
        verify(tasks, never()).updateById(any(AiCodingTaskEntity.class));
        verifyNoInteractions(events);
        verify(handoffs, never()).closeTaskHandoffs(anyString(), anyString());
    }

    @Test
    void successfulAcceptanceUsesTheExistingTerminalTransition() {
        task.setExecutionStatus("ACCEPTANCE_READY");
        when(provider.readiness(any(), any())).thenReturn(List.of(item("E2E_READY", "PASS")));
        var completed = service.finishAcceptance("task-1", true, "Accepted", "reviewer");
        assertEquals("COMPLETED", completed.executionStatus());
        assertNotNull(completed.completedAt());
        verify(handoffs).closeTaskHandoffs("task-1", "TASK_COMPLETED");
        verify(questions).update(isNull(), any());
    }

    @Test
    void aTaskWithoutDeclaredAcceptanceGatesCannotBeManuallyVerified() {
        when(provider.acceptanceReadinessKeys()).thenReturn(List.of());
        assertThrows(IllegalStateException.class, () -> service.verifyAcceptanceReadiness("task-1"));
        verify(provider, never()).readiness(any(), any());
    }

    private ReadinessItem item(String key, String status) {
        return new ReadinessItem(key, "End-to-end", status, "server observation", null);
    }
}
