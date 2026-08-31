package com.enterprise.ai.control.runtime;

import com.enterprise.ai.control.context.PersonalMemoryRecallService;
import com.enterprise.ai.control.context.PersonalMemoryCandidateObservationService;
import com.enterprise.ai.control.internalauth.InternalServiceAuthSigner;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeTrustedAgentExecutionGatewayMemoryTest {

    @Test
    @SuppressWarnings("unchecked")
    void signsPersonalMemoryBesideBodyInsteadOfMixingItIntoWorkflowInput() throws Exception {
        PersonalMemoryRecallService recall = mock(PersonalMemoryRecallService.class);
        when(recall.recall(eq("AGENT"), eq("default"), eq("42"), any())).thenReturn(Map.of(
                "schema", "reachai-personal-memory-context-v1",
                "memories", List.of(Map.of("id", 1, "type", "FACT", "content", "住在青岛"))));
        ObjectMapper mapper = new ObjectMapper();
        RuntimeTrustedAgentExecutionGateway gateway = new RuntimeTrustedAgentExecutionGateway(
                mock(InternalServiceAuthSigner.class), mapper, mock(RuntimeAgentStreamProxy.class),
                "http://localhost:18604", recall);
        Map<String, Object> publicBody = Map.of("agentId", "a1", "message", "我住哪里");

        Map<String, Object> envelope = mapper.readValue(
                gateway.serializeEnvelope(publicBody, "AGENT", "default", "42"), Map.class);

        assertEquals(publicBody, envelope.get("body"));
        assertTrue(envelope.containsKey("personalMemory"));
        assertFalse(((Map<String, Object>) envelope.get("body")).containsKey("personalMemory"));
        assertEquals("42", ((Map<String, Object>) envelope.get("identity")).get("userId"));
    }

    @Test
    void candidateExtractionRunsOnlyForARealSuccessfulExecution() throws Exception {
        PersonalMemoryCandidateObservationService observation = mock(PersonalMemoryCandidateObservationService.class);
        RuntimeTrustedAgentExecutionGateway gateway = new RuntimeTrustedAgentExecutionGateway(
                mock(InternalServiceAuthSigner.class), new ObjectMapper(), mock(RuntimeAgentStreamProxy.class),
                "http://localhost:18604", null, observation);
        Method method = RuntimeTrustedAgentExecutionGateway.class.getDeclaredMethod(
                "observeCompletedTurn", Map.class, Map.class, String.class, String.class, String.class);
        method.setAccessible(true);
        Map<String, Object> request = Map.of("message", "请记住我住在青岛", "agentId", "a1");

        method.invoke(gateway, request, Map.of("success", false, "answer", "failed"),
                "AGENT", "default", "42");
        verify(observation, never()).observeExplicitIfPresent(any(), any(), any(), any());

        when(observation.observeExplicitIfPresent(any(), any(), any(), any())).thenReturn(true);
        method.invoke(gateway, request, Map.of("success", true, "answer", "记住了"),
                "AGENT", "default", "42");
        verify(observation).observeExplicitIfPresent(eq("AGENT"), eq("default"), eq("42"), any());
    }
}
