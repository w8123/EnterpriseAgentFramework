package com.enterprise.ai.control.runops;

import com.enterprise.ai.control.aicoding.application.AiCodingTaskApplicationService;
import com.enterprise.ai.control.aicoding.application.port.AiCodingReusableTaskQuery;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.CreateTaskCommand;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskView;
import com.enterprise.ai.control.client.capability.CapabilityProjectOnboardingClient;
import com.enterprise.ai.control.runops.TraceWorkflowCandidateApplicationService.CreateCandidateTaskRequest;
import com.enterprise.ai.control.runops.TraceWorkflowCandidateEligibility.EligibilityView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TraceWorkflowCandidateApplicationServiceTest {

    private final TraceWorkflowCandidateEligibility eligibilityService =
            mock(TraceWorkflowCandidateEligibility.class);
    private final CapabilityProjectOnboardingClient capabilityClient =
            mock(CapabilityProjectOnboardingClient.class);
    private final AiCodingTaskApplicationService taskService =
            mock(AiCodingTaskApplicationService.class);
    private final AiCodingReusableTaskQuery reusableTasks = mock(AiCodingReusableTaskQuery.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TraceWorkflowCandidateApplicationService service =
            new TraceWorkflowCandidateApplicationService(
                    eligibilityService,
                    capabilityClient,
                    taskService,
                    reusableTasks,
                    objectMapper);

    @BeforeEach
    void eligibleTrace() {
        when(eligibilityService.evaluate("trace-1"))
                .thenReturn(eligibility(true));
    }

    @Test
    void createsOneGovernedTaskForRegisteredTraceProject() {
        when(capabilityClient.getProjectByCode("orders"))
                .thenReturn(Map.of("id", 7L, "projectCode", "orders"));
        TaskView created = task("ait-created", "READY");
        when(taskService.create(any())).thenReturn(created);

        var result = service.createOrReuse(
                "trace-1",
                new CreateCandidateTaskRequest("CODEX", "alice"));

        assertTrue(result.created());
        assertEquals("ait-created", result.task().taskId());
        ArgumentCaptor<CreateTaskCommand> command =
                ArgumentCaptor.forClass(CreateTaskCommand.class);
        verify(taskService).create(command.capture());
        assertEquals(7L, command.getValue().projectId());
        assertEquals(TraceWorkflowCandidateTaskProvider.TASK_KIND,
                command.getValue().taskKind());
        assertEquals("trace-1",
                command.getValue().targets().get(0).targetKey());
        assertEquals(22L,
                command.getValue().targets().get(0).snapshot()
                        .path("sourceWorkflowVersionId").asLong());
    }

    @Test
    void reusesUnfinishedTaskForSameTraceAndExecutor() {
        when(reusableTasks.latestForPrimaryTarget(TraceWorkflowCandidateTaskProvider.TASK_KIND, "CODEX",
                TraceWorkflowCandidateEligibility.PRIMARY_TARGET_TYPE, "trace-1")).thenReturn(Optional.of("ait-existing"));
        TaskView existing = task("ait-existing", "RUNNING");
        when(taskService.task("ait-existing")).thenReturn(existing);

        var result = service.createOrReuse(
                "trace-1",
                new CreateCandidateTaskRequest("CODEX", null));

        assertFalse(result.created());
        assertEquals("ait-existing", result.task().taskId());
        verify(taskService, never()).create(any());
        verify(capabilityClient, never()).getProjectByCode(any());
    }

    @Test
    void refusesIneligibleTraceBeforeAnyTaskWrite() {
        when(eligibilityService.evaluate("trace-1"))
                .thenReturn(eligibility(false));

        assertThrows(IllegalArgumentException.class,
                () -> service.createOrReuse("trace-1", null));

        verify(taskService, never()).create(any());
        verify(capabilityClient, never()).getProjectByCode(any());
    }

    private EligibilityView eligibility(boolean eligible) {
        return new EligibilityView(
                TraceWorkflowCandidateEligibility.ELIGIBILITY_SCHEMA,
                "trace-1",
                "orders",
                eligible,
                eligible ? List.of() : List.of("not read-only"),
                List.of("read-only evidence"),
                "wf-source",
                22L,
                "v1.0.1",
                objectMapper.createObjectNode());
    }

    private TaskView task(String taskId, String status) {
        return new TaskView(
                taskId,
                7L,
                "orders",
                "RUNOPS_TRACE_TO_WORKFLOW",
                TraceWorkflowCandidateTaskProvider.TASK_KIND,
                "v1",
                "CODEX",
                "candidate",
                "candidate",
                "READ_WRITE",
                status,
                TraceWorkflowCandidateTaskProvider.CONTRACT_KEY,
                TraceWorkflowCandidateTaskProvider.CONTRACT_VERSION,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                List.of());
    }
}
