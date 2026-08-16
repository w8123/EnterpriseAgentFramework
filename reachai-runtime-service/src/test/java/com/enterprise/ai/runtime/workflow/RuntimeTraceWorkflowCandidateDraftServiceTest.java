package com.enterprise.ai.runtime.workflow;

import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.runtime.workflow.RuntimeTraceWorkflowCandidateDraftService.DraftRequest;
import com.enterprise.ai.runtime.workflow.aicoding.RuntimeWorkflowAiCodingService;
import com.enterprise.ai.runtime.workflow.aicoding.RuntimeWorkflowAiCodingService.ContextView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeTraceWorkflowCandidateDraftServiceTest {

    private final RuntimeWorkflowAiCodingService aiCodingService =
            mock(RuntimeWorkflowAiCodingService.class);
    private final RuntimeWorkflowDefinitionService definitionService =
            mock(RuntimeWorkflowDefinitionService.class);
    private final RuntimeWorkflowReleaseValidationService validationService =
            mock(RuntimeWorkflowReleaseValidationService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RuntimeTraceWorkflowCandidateDraftService service =
            new RuntimeTraceWorkflowCandidateDraftService(
                    aiCodingService,
                    definitionService,
                    validationService,
                    objectMapper);

    @BeforeEach
    void validationPasses() {
        when(validationService.validateProposed(any(), any()))
                .thenReturn(RuntimeWorkflowReleaseValidationResult.builder().build());
    }

    @Test
    void createsNewTaskOwnedDraft() {
        DraftRequest request = request();
        ContextView created = context("wf-new");
        when(definitionService.findByKeySlug("candidate-ait123"))
                .thenReturn(Optional.empty());
        when(aiCodingService.createWorkflow(any())).thenReturn(created);

        assertEquals(created, service.createOrReplace(request));
        verify(aiCodingService).createWorkflow(any());
    }

    @Test
    void replacesOnlyDraftOwnedBySameTaskAndTrace() throws Exception {
        DraftRequest request = request();
        RuntimeWorkflowDefinitionEntity existing = existingOwnedDraft();
        ContextView replaced = context("wf-existing");
        when(definitionService.findByKeySlug("candidate-ait123"))
                .thenReturn(Optional.of(existing));
        when(aiCodingService.replaceDraft(eq("wf-existing"), any()))
                .thenReturn(replaced);

        assertEquals(replaced, service.createOrReplace(request));
        verify(aiCodingService).replaceDraft(eq("wf-existing"), any());
    }

    @Test
    void refusesKeyCollisionOwnedByAnotherTask() throws Exception {
        RuntimeWorkflowDefinitionEntity existing = existingOwnedDraft();
        Map<String, Object> metadata = objectMapper.readValue(
                existing.getExtraJson(), Map.class);
        metadata.put("taskId", "ait_other");
        existing.setExtraJson(objectMapper.writeValueAsString(metadata));
        when(definitionService.findByKeySlug("candidate-ait123"))
                .thenReturn(Optional.of(existing));

        assertThrows(IllegalArgumentException.class,
                () -> service.createOrReplace(request()));
    }

    private DraftRequest request() {
        GraphSpec graph = new GraphSpec();
        GraphSpec.Node node = new GraphSpec.Node();
        node.setId("input");
        node.setType("USER_INPUT");
        graph.setNodes(List.of(node));
        graph.setEdges(List.of());
        graph.setEntryNodeId("input");
        graph.setExitNodeIds(List.of("input"));
        return new DraftRequest(
                "ait_123",
                "trace-1",
                "wf-source",
                22L,
                "v1.0.1",
                7L,
                "orders",
                "查询候选",
                "candidate-ait123",
                "只读候选",
                "GENERAL",
                null,
                graph,
                null);
    }

    private RuntimeWorkflowDefinitionEntity existingOwnedDraft() throws Exception {
        RuntimeWorkflowDefinitionEntity entity = new RuntimeWorkflowDefinitionEntity();
        entity.setId("wf-existing");
        entity.setProjectId(7L);
        entity.setProjectCode("orders");
        entity.setStatus("DRAFT");
        entity.setExtraJson(objectMapper.writeValueAsString(Map.of(
                "source", "RUNOPS_TRACE_CANDIDATE",
                "taskId", "ait_123",
                "sourceTraceId", "trace-1",
                "sourceWorkflowId", "wf-source",
                "sourceWorkflowVersionId", 22L,
                "sourceWorkflowVersion", "v1.0.1")));
        return entity;
    }

    private ContextView context(String id) {
        return new ContextView(
                new RuntimeWorkflowAiCodingService.WorkflowSnapshot(
                        id, "candidate-ait123", "候选", null,
                        7L, "orders", "GENERAL", "GRAPH_SPEC",
                        "USER", "AI_CODING", null, "DRAFT",
                        LocalDateTime.now()),
                null, null, null, List.of(), Map.of(), Map.of(),
                List.of(), List.of(), List.of());
    }
}
