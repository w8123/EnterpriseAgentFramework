package com.enterprise.ai.runtime.supervisor;

import com.enterprise.ai.runtime.agent.RuntimeAgentConfigVersionEntity;
import com.enterprise.ai.runtime.agent.RuntimeAgentSkillBindingEntity;
import com.enterprise.ai.runtime.agent.RuntimeAgentSkillRepositoryFactory.SkippedSkill;
import com.enterprise.ai.runtime.agent.RuntimeAgentView;
import com.enterprise.ai.runtime.runops.RuntimeGuardDecisionLogMapper;
import com.enterprise.ai.runtime.runops.RuntimeRunLifecycleService;
import com.enterprise.ai.runtime.trace.RuntimeTraceSpanEntity;
import com.enterprise.ai.runtime.trace.RuntimeTraceSpanMapper;
import com.enterprise.ai.runtime.trace.RuntimeToolCallLogMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SupervisorExecutionTraceSkillTest {

    @Test
    void recordsPinnedBindingsAndAgentScopeActivationWithoutSkillContents() throws Exception {
        RuntimeTraceSpanMapper spanMapper = mock(RuntimeTraceSpanMapper.class);
        RuntimeRunLifecycleService runLifecycle = mock(RuntimeRunLifecycleService.class);
        ObjectMapper objectMapper = new ObjectMapper();
        SupervisorExecutionTraceService service = new SupervisorExecutionTraceService(
                spanMapper,
                mock(RuntimeToolCallLogMapper.class),
                mock(RuntimeGuardDecisionLogMapper.class),
                runLifecycle,
                objectMapper);
        RuntimeTraceSpanEntity root = new RuntimeTraceSpanEntity();
        root.setId(7L);
        root.setTraceId("trace-1");
        root.setSpanId("root-span");
        root.setMetadataJson("{\"agentConfigVersionId\":41}");
        when(spanMapper.selectById(7L)).thenReturn(root);
        RuntimeAgentSkillBindingEntity binding = binding();
        SupervisorExecutionTraceService.TraceHandle trace =
                new SupervisorExecutionTraceService.TraceHandle(
                        "trace-1", "root-span", 7L, LocalDateTime.now());

        service.skillBindings(trace, List.of(binding));

        JsonNode rootMetadata = objectMapper.readTree(root.getMetadataJson());
        assertEquals(1, rootMetadata.path("skillBindingCount").asInt());
        assertEquals("reachai/demo-skill@1.2.3#" + "a".repeat(64),
                rootMetadata.path("skillBindings").get(0).path("identity").asText());
        assertFalse(root.getMetadataJson().contains("secret skill instruction"));
        verify(spanMapper).updateById(root);
        verify(runLifecycle).recordSkillBindings("trace-1", List.of(binding));

        RuntimeAgentConfigVersionEntity config = new RuntimeAgentConfigVersionEntity();
        config.setId(41L);
        config.setAgentId("agent-1");
        config.setVersionNo(2);
        RuntimeAgentView agent = new RuntimeAgentView(
                "agent-1", 1L, "project", "demo-agent", "Demo Agent", "description",
                "PROJECT", null, true, 41L, 41L, 2, "ACTIVE", "AGENTSCOPE", 0, null, null);
        service.skillActivation(trace, agent, config, Map.of("message", "hello"), List.of(binding),
                List.of(new SkippedSkill(12L, 22L, "reachai/optional-skill", "2.0.0",
                        "STATUS_REVOKED")));

        ArgumentCaptor<RuntimeTraceSpanEntity> inserted =
                ArgumentCaptor.forClass(RuntimeTraceSpanEntity.class);
        verify(spanMapper).insert(inserted.capture());
        assertEquals("SKILL_CONTEXT", inserted.getValue().getSpanType());
        assertEquals("SUCCESS", inserted.getValue().getStatus());
        assertTrue(inserted.getValue().getMetadataJson().contains("AGENTSCOPE_REPOSITORY_BOUND"));
        assertTrue(inserted.getValue().getMetadataJson().contains("\"scriptExecutionEnabled\":false"));
        assertTrue(inserted.getValue().getMetadataJson().contains("STATUS_REVOKED"));
        assertTrue(inserted.getValue().getOutputSummary().contains("\"skippedSkillCount\":1"));
    }

    private RuntimeAgentSkillBindingEntity binding() {
        RuntimeAgentSkillBindingEntity binding = new RuntimeAgentSkillBindingEntity();
        binding.setSkillId(11L);
        binding.setSkillVersionId(21L);
        binding.setPublisher("reachai");
        binding.setStandardName("demo-skill");
        binding.setVersion("1.2.3");
        binding.setSourceSha256("a".repeat(64));
        binding.setActivationMode("MODEL_SELECTED");
        binding.setScriptPolicy("DENY");
        binding.setRequired(true);
        return binding;
    }
}
