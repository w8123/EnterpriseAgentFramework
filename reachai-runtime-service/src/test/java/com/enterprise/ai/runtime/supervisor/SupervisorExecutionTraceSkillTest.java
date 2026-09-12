package com.enterprise.ai.runtime.supervisor;

import com.enterprise.ai.runtime.agent.RuntimeAgentConfigSnapshot;
import com.enterprise.ai.runtime.agent.RuntimeAgentSkillBindingSnapshot;
import com.enterprise.ai.runtime.agent.RuntimeAgentSkillRepositoryFactory.SkippedSkill;
import com.enterprise.ai.runtime.agent.RuntimeAgentView;
import com.enterprise.ai.runtime.runops.RuntimeRunLifecycleService;
import com.enterprise.ai.runtime.runops.RuntimeRunSnapshots;
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

    @org.junit.jupiter.api.BeforeAll
    static void initializeTraceLambdaMetadata() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""), RuntimeTraceSpanEntity.class);
    }

    @Test
    void recordsPinnedBindingsAndAgentScopeActivationWithoutSkillContents() throws Exception {
        RuntimeTraceSpanMapper spanMapper = mock(RuntimeTraceSpanMapper.class);
        RuntimeRunLifecycleService runLifecycle = mock(RuntimeRunLifecycleService.class);
        ObjectMapper objectMapper = new ObjectMapper();
        SupervisorExecutionTraceService service = new SupervisorExecutionTraceService(
                new com.enterprise.ai.runtime.trace.RuntimeTraceEvidenceWriter(spanMapper, mock(RuntimeToolCallLogMapper.class)),
                runLifecycle,
                objectMapper,
                new com.enterprise.ai.runtime.trace.RuntimeTraceRootService(spanMapper, objectMapper),
                new com.enterprise.ai.runtime.trace.RuntimeTraceSpanTerminationService(spanMapper));
        RuntimeTraceSpanEntity root = new RuntimeTraceSpanEntity();
        root.setId(7L);
        root.setTraceId("trace-1");
        root.setSpanId("root-span");
        root.setSpanType("SUPERVISOR");
        root.setMetadataJson("{\"agentConfigVersionId\":41}");
        when(spanMapper.selectByIdForUpdate(7L)).thenReturn(root);
        when(spanMapper.update(org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.any())).thenReturn(1);
        RuntimeAgentSkillBindingSnapshot binding = binding();
        SupervisorExecutionTraceService.TraceHandle trace =
                new SupervisorExecutionTraceService.TraceHandle(
                        "trace-1", "root-span", 7L, LocalDateTime.now());

        service.skillBindings(trace, List.of(binding));

        @SuppressWarnings({"rawtypes", "unchecked"})
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<RuntimeTraceSpanEntity>> update =
                ArgumentCaptor.forClass((Class) com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class);
        verify(spanMapper).update(org.mockito.ArgumentMatchers.isNull(), update.capture());
        update.getValue().getSqlSet();
        String metadataJson = update.getValue().getParamNameValuePairs().values().stream()
                .filter(value -> value instanceof String text && text.contains("skillBindingCount"))
                .map(Object::toString).findFirst().orElseThrow();
        JsonNode rootMetadata = objectMapper.readTree(metadataJson);
        assertEquals(1, rootMetadata.path("skillBindingCount").asInt());
        assertEquals("reachai/demo-skill@1.2.3#" + "a".repeat(64),
                rootMetadata.path("skillBindings").get(0).path("identity").asText());
        assertFalse(metadataJson.contains("secret skill instruction"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RuntimeRunSnapshots.SkillBinding>> snapshots = ArgumentCaptor.forClass(List.class);
        verify(runLifecycle).recordSkillBindings(org.mockito.ArgumentMatchers.eq("trace-1"), snapshots.capture());
        binding = binding.toBuilder().version("later-edit").build();
        assertEquals("1.2.3", snapshots.getValue().get(0).version());
        assertEquals("a".repeat(64), snapshots.getValue().get(0).sourceSha256());
        assertEquals(objectMapper.readTree(objectMapper.writeValueAsString(snapshots.getValue().get(0).traceMetadata())),
                rootMetadata.path("skillBindings").get(0));
        binding = binding.toBuilder().version("1.2.3").build();

        RuntimeAgentConfigSnapshot config = RuntimeAgentConfigSnapshot.builder()
                .id(41L)
                .agentId("agent-1")
                .versionNo(2)
                .build();
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

    private RuntimeAgentSkillBindingSnapshot binding() {
        RuntimeAgentSkillBindingSnapshot binding = RuntimeAgentSkillBindingSnapshot.builder()
                .skillId(11L)
                .skillVersionId(21L)
                .publisher("reachai")
                .standardName("demo-skill")
                .version("1.2.3")
                .sourceSha256("a".repeat(64))
                .activationMode("MODEL_SELECTED")
                .scriptPolicy("DENY")
                .required(true)
                .sourceRoot("private-skill-root")
                .packageManifestJson("{\"instruction\":\"secret skill instruction\"}")
                .build();
        return binding;
    }
}
