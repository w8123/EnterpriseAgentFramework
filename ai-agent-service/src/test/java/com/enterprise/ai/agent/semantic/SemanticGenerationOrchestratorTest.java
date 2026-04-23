package com.enterprise.ai.agent.semantic;

import com.enterprise.ai.agent.scan.ScanModuleService;
import com.enterprise.ai.agent.scan.ScanProjectEntity;
import com.enterprise.ai.agent.scan.ScanProjectService;
import com.enterprise.ai.agent.semantic.context.SemanticContextCollector;
import com.enterprise.ai.agent.semantic.llm.SemanticLlmClient;
import com.enterprise.ai.agent.semantic.prompt.PromptTemplateRegistry;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SemanticGenerationOrchestratorTest {

    @Test
    void extractToolSummaryPicksFirstSection() {
        SemanticGenerationOrchestrator o = newOrchestrator();
        String md = "## 一句话语义\n创建订单并锁定库存。\n\n## 使用场景\n...";
        assertEquals("创建订单并锁定库存。", o.extractToolSummary(md));
    }

    @Test
    void extractToolSummaryFallbackWhenHeaderMissing() {
        SemanticGenerationOrchestrator o = newOrchestrator();
        String md = "这是没有标题的文档。";
        assertEquals(md, o.extractToolSummary(md));
    }

    @Test
    void extractToolSummaryNullInput() {
        SemanticGenerationOrchestrator o = newOrchestrator();
        assertNull(o.extractToolSummary(null));
    }

    @Test
    @SuppressWarnings("unchecked")
    void startProjectBatchRejectsConcurrentRun() {
        ScanProjectService projectService = mock(ScanProjectService.class);
        when(projectService.getById(1L)).thenReturn(new ScanProjectEntity());

        SemanticGenerationOrchestrator orchestrator = new SemanticGenerationOrchestrator(
                projectService,
                mock(ScanModuleService.class),
                mock(SemanticContextCollector.class),
                mock(PromptTemplateRegistry.class),
                mock(SemanticLlmClient.class),
                mock(SemanticDocService.class),
                mock(ToolDefinitionMapper.class));

        // 直接占住锁，避免启动真正的批量 async。
        java.util.Map<Long, String> locks =
                (java.util.Map<Long, String>) getField(orchestrator, "projectLocks");
        locks.put(1L, "pre-existing-task");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> orchestrator.startProjectBatch(1L, false));
        assertTrue(ex.getMessage().contains("进行中"));
    }

    private SemanticGenerationOrchestrator newOrchestrator() {
        return new SemanticGenerationOrchestrator(
                mock(ScanProjectService.class),
                mock(ScanModuleService.class),
                mock(SemanticContextCollector.class),
                mock(PromptTemplateRegistry.class),
                mock(SemanticLlmClient.class),
                mock(SemanticDocService.class),
                mock(ToolDefinitionMapper.class));
    }

    private Object getField(Object target, String name) {
        try {
            java.lang.reflect.Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(target);
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException(ex);
        }
    }
}
