package com.enterprise.ai.runtime.a2a;

import com.enterprise.ai.runtime.agent.RuntimeAgentConfigVersionEntity;
import com.enterprise.ai.runtime.agent.RuntimeAgentConfigVersionMapper;
import com.enterprise.ai.runtime.client.control.RuntimeA2aControlClient;
import com.enterprise.ai.runtime.client.control.RuntimeA2aControlClient.SendRequest;
import com.enterprise.ai.runtime.client.control.RuntimeA2aControlClient.SendResponse;
import com.enterprise.ai.runtime.agent.RuntimeAgentRemoteBindingEntity;
import com.enterprise.ai.runtime.agent.RuntimeAgentRemoteBindingMapper;
import com.enterprise.ai.runtime.agent.RuntimeAgentRemoteBindingReader;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeA2aDelegationServiceTest {

    private final RuntimeAgentRemoteBindingMapper bindingMapper =
            mock(RuntimeAgentRemoteBindingMapper.class);
    private final RuntimeAgentConfigVersionMapper configMapper =
            mock(RuntimeAgentConfigVersionMapper.class);
    private final RuntimeA2aControlClient controlClient = mock(RuntimeA2aControlClient.class);
    private final RuntimeA2aDelegationService service = new RuntimeA2aDelegationService(
            new RuntimeAgentRemoteBindingReader(bindingMapper, configMapper), controlClient, new ObjectMapper());

    @Test
    void delegatesOnlyThroughTheActiveFixedBindingAndPropagatesAttestedReferences() {
        when(configMapper.selectById(23L)).thenReturn(activeConfig());
        when(bindingMapper.selectOne(any())).thenReturn(binding());
        SendResponse expected = new SendResponse(
                "reachai.a2a-hub.outbound-delegation.v1", "task-local", "context-local",
                "task-remote", "context-remote", "TASK_STATE_COMPLETED", "completed",
                null, false, List.of("{\"messageId\":\"answer-1\"}"), List.of());
        when(controlClient.send(any(), eq(45_000L))).thenReturn(expected);

        SendResponse actual = service.send("agent-1", 23L, 31L,
                new RuntimeA2aDelegationService.DelegationRequest(
                        "session-1", null, null, "message-1", "Review this change",
                        "review", "CONFIDENTIAL", List.of("text/plain"), 0, "trace-1"));

        assertEquals(expected, actual);
        ArgumentCaptor<SendRequest> request = ArgumentCaptor.forClass(SendRequest.class);
        verify(controlClient).send(request.capture(), eq(45_000L));
        assertEquals(31L, request.getValue().bindingId());
        assertEquals("agent-1", request.getValue().runtimeAgentId());
        assertEquals(23L, request.getValue().agentConfigVersionId());
        assertEquals(41L, request.getValue().principalId());
        assertEquals(51L, request.getValue().remoteAgentId());
        assertEquals(61L, request.getValue().remoteAgentRevisionId());
        assertEquals("review", request.getValue().protocolSkillId());
        assertEquals("CONFIDENTIAL", request.getValue().contentClassification());
    }

    @Test
    void rejectsSkillOutsideImmutableBindingBeforeCallingControl() {
        when(configMapper.selectById(23L)).thenReturn(activeConfig());
        when(bindingMapper.selectOne(any())).thenReturn(binding());

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> service.send("agent-1", 23L, 31L,
                        new RuntimeA2aDelegationService.DelegationRequest(
                                "session-1", null, null, null, "Do something else",
                                "undeclared", "INTERNAL", List.of("text/plain"), 0, null)));

        assertEquals("protocolSkillId is outside the immutable A2A binding allowlist",
                failure.getMessage());
        verify(controlClient, never()).send(any(), anyLong());
    }

    @Test
    void rejectsDraftOrDifferentAgentConfigBeforeLookingUpTheBinding() {
        RuntimeAgentConfigVersionEntity draft = activeConfig();
        draft.setStatus("DRAFT");
        when(configMapper.selectById(23L)).thenReturn(draft);

        assertThrows(IllegalArgumentException.class,
                () -> service.send("agent-1", 23L, 31L,
                        new RuntimeA2aDelegationService.DelegationRequest(
                                "session-1", null, null, null, "Review", "review",
                                "INTERNAL", List.of(), 0, null)));
        verify(bindingMapper, never()).selectOne(any());
        verify(controlClient, never()).send(any(), anyLong());
    }

    private RuntimeAgentConfigVersionEntity activeConfig() {
        RuntimeAgentConfigVersionEntity config = new RuntimeAgentConfigVersionEntity();
        config.setId(23L);
        config.setAgentId("agent-1");
        config.setStatus("ACTIVE");
        return config;
    }

    private RuntimeAgentRemoteBindingEntity binding() {
        RuntimeAgentRemoteBindingEntity binding = new RuntimeAgentRemoteBindingEntity();
        binding.setId(31L);
        binding.setAgentId("agent-1");
        binding.setAgentConfigVersionId(23L);
        binding.setPrincipalId(41L);
        binding.setRemoteAgentId(51L);
        binding.setRemoteAgentRevisionId(61L);
        binding.setRemoteAgentKeySnapshot("partner-reviewer");
        binding.setAllowedSkillIdsJson("[\"review\"]");
        binding.setInputModesJson("[\"text/plain\"]");
        binding.setOutputModesJson("[\"text/plain\"]");
        binding.setTimeoutMs(45_000L);
        binding.setEnabled(true);
        return binding;
    }
}
