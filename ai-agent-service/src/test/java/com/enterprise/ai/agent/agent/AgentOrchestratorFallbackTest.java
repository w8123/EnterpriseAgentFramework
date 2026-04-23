package com.enterprise.ai.agent.agent;

import com.enterprise.ai.agent.agentscope.AgentRouter;
import com.enterprise.ai.agent.config.LLMConfig;
import com.enterprise.ai.agent.model.AgentResult;
import com.enterprise.ai.agent.service.IntentService;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentOrchestratorFallbackTest {

    @Test
    void keepsOnlySafeLegacyWorkflowEntryPoints() {
        Set<String> methodNames = Arrays.stream(AgentWorkflow.class.getDeclaredMethods())
                .map(method -> method.getName())
                .collect(java.util.stream.Collectors.toSet());

        assertFalse(methodNames.contains("executeQueryDataFlow"));
        assertFalse(methodNames.contains("executeBusinessOperationFlow"));
        assertFalse(methodNames.contains("executeCreativeTaskFlow"));
        assertTrue(methodNames.contains("executeKnowledgeQAFlow"));
        assertTrue(methodNames.contains("executeGeneralChatFlow"));
    }

    @Test
    void routesRetiredToolIntentsToGeneralChatDuringFallback() {
        for (String intent : new String[]{"QUERY_DATA", "BUSINESS_OPERATION", "ANALYSIS", "CREATIVE_TASK"}) {
            AgentRouter agentRouter = mock(AgentRouter.class);
            AgentWorkflow agentWorkflow = mock(AgentWorkflow.class);
            IntentService intentService = mock(IntentService.class);
            LLMConfig llmConfig = new LLMConfig();
            llmConfig.setMaxSteps(5);

            when(agentRouter.route("s", "u", "hello", intent))
                    .thenThrow(new RuntimeException("agentscope boom"));
            when(agentWorkflow.executeGeneralChatFlow(any()))
                    .thenReturn(AgentResult.builder().success(true).answer("general").build());

            AgentOrchestrator orchestrator = new AgentOrchestrator(agentRouter, agentWorkflow, intentService, llmConfig);

            AgentResult result = orchestrator.orchestrate("s", "u", "hello", intent);

            assertEquals("general", result.getAnswer());
            assertEquals(intent, result.getMetadata().get("intentType"));
            assertTrue(Boolean.TRUE.equals(result.getMetadata().get("fallback")));
            verify(agentWorkflow).executeGeneralChatFlow(any(AgentContext.class));
            verify(agentWorkflow, never()).executeKnowledgeQAFlow(any());
        }
    }

    @Test
    void keepsKnowledgeQaAsSafeFallbackFlow() {
        AgentRouter agentRouter = mock(AgentRouter.class);
        AgentWorkflow agentWorkflow = mock(AgentWorkflow.class);
        IntentService intentService = mock(IntentService.class);
        LLMConfig llmConfig = new LLMConfig();
        llmConfig.setMaxSteps(5);

        when(agentRouter.route("s", "u", "制度是什么", "KNOWLEDGE_QA"))
                .thenThrow(new RuntimeException("agentscope boom"));
        when(agentWorkflow.executeKnowledgeQAFlow(any()))
                .thenReturn(AgentResult.builder().success(true).answer("knowledge").build());

        AgentOrchestrator orchestrator = new AgentOrchestrator(agentRouter, agentWorkflow, intentService, llmConfig);

        AgentResult result = orchestrator.orchestrate("s", "u", "制度是什么", "KNOWLEDGE_QA");

        assertEquals("knowledge", result.getAnswer());
        assertEquals("KNOWLEDGE_QA", result.getMetadata().get("intentType"));
        assertTrue(Boolean.TRUE.equals(result.getMetadata().get("fallback")));
        verify(agentWorkflow).executeKnowledgeQAFlow(any(AgentContext.class));
        verify(agentWorkflow, never()).executeGeneralChatFlow(any());
    }
}
