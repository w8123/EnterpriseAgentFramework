package com.enterprise.ai.agent.controller;

import com.enterprise.ai.agent.scan.ScanModuleService;
import com.enterprise.ai.agent.semantic.SemanticDocEntity;
import com.enterprise.ai.agent.semantic.SemanticDocService;
import com.enterprise.ai.agent.semantic.SemanticGenerationOrchestrator;
import com.enterprise.ai.agent.semantic.SemanticGenerationTask;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionEntity;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionMapper;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SemanticDocControllerTest {

    @Test
    void batchGenerateAcceptedAndReturnsTaskId() {
        SemanticGenerationOrchestrator orchestrator = mock(SemanticGenerationOrchestrator.class);
        when(orchestrator.startProjectBatch(eq(1L), eq(false), isNull(), isNull())).thenReturn("task-xyz");

        SemanticDocController controller = controller(orchestrator);
        ResponseEntity<?> resp = controller.batchGenerate(1L, false, null, null);

        assertEquals(HttpStatus.ACCEPTED, resp.getStatusCode());
        assertNotNull(resp.getBody());
    }

    @Test
    void batchGenerateConflictsWhenLocked() {
        SemanticGenerationOrchestrator orchestrator = mock(SemanticGenerationOrchestrator.class);
        when(orchestrator.startProjectBatch(eq(2L), eq(false), isNull(), isNull()))
                .thenThrow(new IllegalStateException("项目已有生成任务在进行中: 2"));

        SemanticDocController controller = controller(orchestrator);
        ResponseEntity<?> resp = controller.batchGenerate(2L, false, null, null);

        assertEquals(409, resp.getStatusCode().value());
    }

    @Test
    void statusReturnsLatestTaskWhenTaskIdAbsent() {
        SemanticGenerationOrchestrator orchestrator = mock(SemanticGenerationOrchestrator.class);
        SemanticGenerationTask task = new SemanticGenerationTask();
        task.setTaskId("t1");
        task.setProjectId(1L);
        task.setStage(SemanticGenerationTask.Stage.DONE);
        task.setTotalSteps(3);
        task.setCompletedSteps(3);
        task.setStartedAt(Instant.now());
        task.setFinishedAt(Instant.now());
        when(orchestrator.findLatestByProject(1L)).thenReturn(Optional.of(task));

        SemanticDocController controller = controller(orchestrator);
        ResponseEntity<?> resp = controller.batchStatus(1L, null);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
    }

    @Test
    void statusReturnsOkWithNullBodyWhenNoTask() {
        SemanticGenerationOrchestrator orchestrator = mock(SemanticGenerationOrchestrator.class);
        when(orchestrator.findLatestByProject(9L)).thenReturn(Optional.empty());

        SemanticDocController controller = controller(orchestrator);
        ResponseEntity<SemanticDocController.TaskDTO> resp = controller.batchStatus(9L, null);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(null, resp.getBody());
    }

    @Test
    void generateToolReturnsNotFoundForUnknownTool() {
        SemanticGenerationOrchestrator orchestrator = mock(SemanticGenerationOrchestrator.class);
        ToolDefinitionService toolService = mock(ToolDefinitionService.class);
        when(toolService.findByName("unknown")).thenReturn(Optional.empty());

        SemanticDocController controller = controller(orchestrator, toolService);
        assertEquals(HttpStatus.NOT_FOUND, controller.generateTool("unknown", true, null, null).getStatusCode());
    }

    @Test
    void generateToolReturnsOkForKnownTool() {
        SemanticGenerationOrchestrator orchestrator = mock(SemanticGenerationOrchestrator.class);
        ToolDefinitionService toolService = mock(ToolDefinitionService.class);
        ToolDefinitionEntity tool = new ToolDefinitionEntity();
        tool.setId(11L);
        tool.setName("demo");
        when(toolService.findByName("demo")).thenReturn(Optional.of(tool));

        SemanticDocEntity doc = new SemanticDocEntity();
        doc.setId(1L);
        doc.setLevel(SemanticDocEntity.LEVEL_TOOL);
        doc.setContentMd("## 一句话语义\n创建订单。");
        doc.setStatus(SemanticDocEntity.STATUS_GENERATED);
        when(orchestrator.generateForTool(eq(11L), eq(true), isNull(), isNull())).thenReturn(doc);

        SemanticDocController controller = controller(orchestrator, toolService);
        ResponseEntity<?> resp = controller.generateTool("demo", true, null, null);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
    }

    private SemanticDocController controller(SemanticGenerationOrchestrator orchestrator) {
        return controller(orchestrator, mock(ToolDefinitionService.class));
    }

    private SemanticDocController controller(SemanticGenerationOrchestrator orchestrator,
                                             ToolDefinitionService toolService) {
        return new SemanticDocController(
                orchestrator,
                mock(SemanticDocService.class),
                mock(ScanModuleService.class),
                toolService,
                mock(ToolDefinitionMapper.class));
    }
}
