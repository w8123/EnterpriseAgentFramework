package com.enterprise.ai.control.aicoding.application;

import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskView;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskArtifactEntity;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskArtifactMapper;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskEntity;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskEventMapper;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskMapper;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskQuestionMapper;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskTargetMapper;
import com.enterprise.ai.control.aicoding.provider.AiCodingTaskProviderRegistry;
import com.enterprise.ai.control.aicoding.security.AiCodingSensitiveJsonSanitizer;
import com.enterprise.ai.control.client.capability.CapabilityProjectOnboardingClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AiCodingTaskReadProjectionTest {

    private final AiCodingTaskMapper taskMapper = mock(AiCodingTaskMapper.class);
    private final AiCodingTaskTargetMapper targetMapper =
            mock(AiCodingTaskTargetMapper.class);
    private final AiCodingTaskQuestionMapper questionMapper =
            mock(AiCodingTaskQuestionMapper.class);
    private final AiCodingTaskArtifactMapper artifactMapper =
            mock(AiCodingTaskArtifactMapper.class);
    private final AiCodingHandoffApplicationService handoffService =
            mock(AiCodingHandoffApplicationService.class);
    private final AiCodingTaskApplicationService service =
            new AiCodingTaskApplicationService(
                    taskMapper,
                    targetMapper,
                    mock(AiCodingTaskEventMapper.class),
                    questionMapper,
                    artifactMapper,
                    mock(AiCodingTaskProviderRegistry.class),
                    mock(AiCodingArtifactProviderExecutor.class),
                    handoffService,
                    mock(CapabilityProjectOnboardingClient.class),
                    mock(AiCodingSensitiveJsonSanitizer.class),
                    new ObjectMapper());

    @Test
    void batchesAppliedArtifactsWithoutHydratingTaskRelations() {
        AiCodingTaskEntity latest = task(
                "scan-running",
                "RUNNING",
                LocalDateTime.of(2026, 8, 3, 10, 0));
        AiCodingTaskEntity previous = task(
                "scan-applied",
                "RESULT_APPLIED",
                LocalDateTime.of(2026, 8, 2, 10, 0));
        when(taskMapper.selectList(any())).thenReturn(List.of(latest, previous));

        AiCodingTaskArtifactEntity applied = new AiCodingTaskArtifactEntity();
        applied.setArtifactId(12L);
        applied.setTaskId("scan-applied");
        applied.setProcessingStatus("APPLIED");
        applied.setApplicationResultJson("{\"repositoryBranch\":\"development\"}");
        when(artifactMapper.selectList(any())).thenReturn(List.of(applied));

        var result = service.latestAppliedTaskArtifact(
                "demo-project",
                "PAGE_MAP_SCAN",
                100);

        assertThat(result.latestTaskId()).isEqualTo("scan-running");
        assertThat(result.latestExecutionStatus()).isEqualTo("RUNNING");
        assertThat(result.applicationResult().path("repositoryBranch").asText())
                .isEqualTo("development");
        verify(taskMapper).selectList(any());
        verify(artifactMapper).selectList(any());
        verifyNoInteractions(targetMapper, questionMapper, handoffService);
    }

    @Test
    void reusesLoadedTaskViewsForTheAccessCenterBootstrap() {
        TaskView latest = mock(TaskView.class);
        when(latest.taskId()).thenReturn("scan-running");
        when(latest.taskKind()).thenReturn("PAGE_MAP_SCAN");
        when(latest.executionStatus()).thenReturn("RUNNING");
        TaskView previous = mock(TaskView.class);
        when(previous.taskId()).thenReturn("scan-applied");
        when(previous.taskKind()).thenReturn("PAGE_MAP_SCAN");
        when(previous.executionStatus()).thenReturn("RESULT_APPLIED");

        AiCodingTaskArtifactEntity applied = new AiCodingTaskArtifactEntity();
        applied.setArtifactId(12L);
        applied.setTaskId("scan-applied");
        applied.setProcessingStatus("APPLIED");
        applied.setApplicationResultJson("{\"repositoryBranch\":\"development\"}");
        when(artifactMapper.selectList(any())).thenReturn(List.of(applied));

        var result = service.latestAppliedTaskArtifact(
                List.of(latest, previous),
                "PAGE_MAP_SCAN",
                100);

        assertThat(result.latestTaskId()).isEqualTo("scan-running");
        assertThat(result.applicationResult().path("repositoryBranch").asText())
                .isEqualTo("development");
        verify(artifactMapper).selectList(any());
        verifyNoInteractions(
                taskMapper,
                targetMapper,
                questionMapper,
                handoffService);
    }

    private AiCodingTaskEntity task(
            String taskId,
            String executionStatus,
            LocalDateTime updatedAt) {
        AiCodingTaskEntity task = new AiCodingTaskEntity();
        task.setTaskId(taskId);
        task.setExecutionStatus(executionStatus);
        task.setUpdatedAt(updatedAt);
        return task;
    }
}
