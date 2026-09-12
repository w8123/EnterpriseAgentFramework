package com.enterprise.ai.control.aiassist;

import com.enterprise.ai.control.agentskill.AgentSkillAccessPolicy;
import com.enterprise.ai.control.agentskill.AgentSkillBindingSnapshotAssembler;
import com.enterprise.ai.control.agentskill.AgentSkillCatalogService;
import com.enterprise.ai.control.agentskill.AgentSkillContracts.BindingDescriptor;
import com.enterprise.ai.control.agentskill.AgentSkillContracts.SkillDetail;
import com.enterprise.ai.control.agentskill.AgentSkillContracts.SkillSummary;
import com.enterprise.ai.control.agentskill.AgentSkillContracts.VersionView;
import com.enterprise.ai.control.agentskill.AgentSkillException;
import com.enterprise.ai.control.client.capability.CapabilityProjectOnboardingClient;
import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ControlAiCodingAgentAuthoringServiceTest {

    @Test
    void createsProjectAgentWithActiveSupervisorConfig() {
        Fixture fixture = fixture();
        when(fixture.capabilityClient.getOnboardingProjectById(7L)).thenReturn(project());
        when(fixture.runtimeClient.listAgents(7L, "orders")).thenReturn(ResponseEntity.ok(List.of()));
        when(fixture.runtimeClient.createAgent(anyMap())).thenReturn(ResponseEntity.ok(Map.of(
                "id", "agent-1", "projectId", 7L, "projectCode", "orders",
                "keySlug", "orders-helper", "name", "订单助手")));
        when(fixture.provisioningService.resolveSupervisorModelInstanceId("model-1"))
                .thenReturn("model-1");
        when(fixture.runtimeClient.saveAgentConfigDraft(eq("agent-1"), anyMap()))
                .thenReturn(ResponseEntity.ok(Map.of(
                        "id", 21L, "status", "DRAFT", "skills", List.of(),
                        "tools", List.of(), "remoteAgents", List.of())));
        when(fixture.runtimeClient.publishAgentConfigVersion(eq("agent-1"), eq(21L), anyMap()))
                .thenReturn(ResponseEntity.ok(Map.of(
                        "id", 21L, "status", "ACTIVE", "skills", List.of(),
                        "tools", List.of(), "remoteAgents", List.of())));

        Map<String, Object> result = fixture.service.createAgent(7L, Map.of(
                "keySlug", "orders-helper",
                "name", "订单助手",
                "supervisorConfig", Map.of(
                        "modelInstanceId", "model-1",
                        "systemPrompt", "你是订单助手。"),
                "requestedBy", "Codex"));

        assertEquals("agent-ai-coding-create.v1", result.get("schema"));
        assertEquals(true, result.get("activated"));
        ArgumentCaptor<Map<String, Object>> identity = ArgumentCaptor.forClass(Map.class);
        verify(fixture.runtimeClient).createAgent(identity.capture());
        assertEquals(7L, identity.getValue().get("projectId"));
        assertEquals("orders", identity.getValue().get("projectCode"));
        assertEquals("PROJECT", identity.getValue().get("visibility"));
        ArgumentCaptor<Map<String, Object>> config = ArgumentCaptor.forClass(Map.class);
        verify(fixture.runtimeClient).saveAgentConfigDraft(eq("agent-1"), config.capture());
        assertEquals("AGENTSCOPE", config.getValue().get("runtimeType"));
        assertEquals("model-1", config.getValue().get("modelInstanceId"));
        assertEquals(List.of(), config.getValue().get("skills"));
        verify(fixture.runtimeClient).publishAgentConfigVersion(
                "agent-1", 21L, Map.of("publishedBy", "Codex"));
    }

    @Test
    void attachesPublishedSkillAndPublishesNewAgentConfig() {
        Fixture fixture = fixture();
        when(fixture.capabilityClient.getOnboardingProjectById(7L)).thenReturn(project());
        when(fixture.runtimeClient.getAgent("agent-1")).thenReturn(ResponseEntity.ok(agent()));
        when(fixture.runtimeClient.listAgentConfigVersions("agent-1")).thenReturn(ResponseEntity.ok(List.of(
                Map.of("id", 20L, "status", "ACTIVE", "skills", List.of(),
                        "tools", List.of(), "remoteAgents", List.of()))));
        BindingDescriptor binding = binding();
        when(fixture.skillCatalogService.publishedBindingDescriptor(11L, 21L)).thenReturn(binding);
        Map<String, Object> canonicalBinding = Map.of(
                "skillId", 11L, "skillVersionId", 21L, "publisher", "reachai",
                "name", "demo-skill", "activationMode", "MODEL_SELECTED",
                "scriptPolicy", "DENY", "required", false, "enabled", true);
        when(fixture.skillSnapshotAssembler.assembleForAiCodingProject(anyMap(), eq("orders")))
                .thenReturn(Map.of("skills", List.of(canonicalBinding)));
        when(fixture.runtimeClient.saveAgentConfigDraft(eq("agent-1"), anyMap()))
                .thenReturn(ResponseEntity.ok(Map.of(
                        "id", 22L, "status", "DRAFT", "skills", List.of(canonicalBinding),
                        "tools", List.of(), "remoteAgents", List.of())));
        when(fixture.runtimeClient.publishAgentConfigVersion(eq("agent-1"), eq(22L), anyMap()))
                .thenReturn(ResponseEntity.ok(Map.of(
                        "id", 22L, "status", "ACTIVE", "skills", List.of(canonicalBinding),
                        "tools", List.of(), "remoteAgents", List.of())));

        Map<String, Object> result = fixture.service.attachSkill(7L, "agent-1", Map.of(
                "skillId", 11L,
                "skillVersionId", 21L,
                "baseConfigVersionId", 20L,
                "requestedBy", "Codex"));

        assertEquals("agent-skill-attachment.v1", result.get("schema"));
        assertEquals(true, result.get("created"));
        assertEquals(true, result.get("published"));
        verify(fixture.skillAccessPolicy).requireProjectCredentialBind(binding.skill(), "orders");
        verify(fixture.runtimeClient).publishAgentConfigVersion(
                "agent-1", 22L, Map.of("publishedBy", "Codex"));
    }

    @Test
    void refusesToMergeIntoUnreadExistingDraft() {
        Fixture fixture = fixture();
        when(fixture.capabilityClient.getOnboardingProjectById(7L)).thenReturn(project());
        when(fixture.runtimeClient.getAgent("agent-1")).thenReturn(ResponseEntity.ok(agent()));
        when(fixture.runtimeClient.listAgentConfigVersions("agent-1")).thenReturn(ResponseEntity.ok(List.of(
                Map.of("id", 23L, "status", "DRAFT", "skills", List.of(),
                        "tools", List.of(), "remoteAgents", List.of()),
                Map.of("id", 20L, "status", "ACTIVE", "skills", List.of(),
                        "tools", List.of(), "remoteAgents", List.of()))));
        BindingDescriptor binding = binding();
        when(fixture.skillCatalogService.publishedBindingDescriptor(11L, 21L)).thenReturn(binding);

        ControlAiCodingAgentException failure = assertThrows(ControlAiCodingAgentException.class,
                () -> fixture.service.attachSkill(7L, "agent-1", Map.of(
                        "skillId", 11L,
                        "skillVersionId", 21L)));

        assertEquals(HttpStatus.CONFLICT, failure.status());
        assertEquals("AGENT_DRAFT_CONFLICT", failure.toBody().get("code"));
        verify(fixture.runtimeClient, never()).saveAgentConfigDraft(eq("agent-1"), anyMap());
    }

    @Test
    void validatesSupervisorModelBeforeCreatingAgentIdentity() {
        Fixture fixture = fixture();
        when(fixture.capabilityClient.getOnboardingProjectById(7L)).thenReturn(project());
        when(fixture.provisioningService.resolveSupervisorModelInstanceId("missing-model"))
                .thenThrow(new IllegalArgumentException("not active"));

        ControlAiCodingAgentException failure = assertThrows(ControlAiCodingAgentException.class,
                () -> fixture.service.createAgent(7L, Map.of(
                        "keySlug", "orders-helper",
                        "name", "订单助手",
                        "supervisorConfig", Map.of("modelInstanceId", "missing-model"))));

        assertEquals(HttpStatus.CONFLICT, failure.status());
        assertEquals("AGENT_MODEL_NOT_AVAILABLE", failure.toBody().get("code"));
        verify(fixture.runtimeClient, never()).createAgent(anyMap());
    }

    @Test
    void doesNotReuseAnActiveSkillBindingThatHasHumanApprovedScripts() {
        Fixture fixture = fixture();
        when(fixture.capabilityClient.getOnboardingProjectById(7L)).thenReturn(project());
        when(fixture.runtimeClient.getAgent("agent-1")).thenReturn(ResponseEntity.ok(agent()));
        when(fixture.runtimeClient.listAgentConfigVersions("agent-1")).thenReturn(ResponseEntity.ok(List.of(
                Map.of(
                        "id", 20L,
                        "status", "ACTIVE",
                        "skills", List.of(Map.of(
                                "skillId", 11L,
                                "skillVersionId", 21L,
                                "activationMode", "MODEL_SELECTED",
                                "scriptPolicy", "SANDBOX_REVIEWED",
                                "required", false,
                                "enabled", true)),
                        "tools", List.of(),
                        "remoteAgents", List.of()))));
        BindingDescriptor binding = binding();
        when(fixture.skillCatalogService.publishedBindingDescriptor(11L, 21L)).thenReturn(binding);
        when(fixture.skillSnapshotAssembler.assembleForAiCodingProject(anyMap(), eq("orders")))
                .thenThrow(AgentSkillException.forbidden("scripts require human approval"));

        ControlAiCodingAgentException failure = assertThrows(ControlAiCodingAgentException.class,
                () -> fixture.service.attachSkill(7L, "agent-1", Map.of(
                        "skillId", 11L,
                        "skillVersionId", 21L)));

        assertEquals(HttpStatus.FORBIDDEN, failure.status());
        assertEquals("SKILL_ACCESS_DENIED", failure.toBody().get("code"));
        verify(fixture.runtimeClient, never()).saveAgentConfigDraft(eq("agent-1"), anyMap());
    }

    @Test
    void bindableCatalogDoesNotExposeInternalOwnerOrReviewerIdentity() {
        Fixture fixture = fixture();
        BindingDescriptor binding = binding();
        when(fixture.capabilityClient.getOnboardingProjectById(7L)).thenReturn(project());
        when(fixture.skillCatalogService.list(null, AgentSkillCatalogService.STATUS_PUBLISHED))
                .thenReturn(List.of(binding.skill()));
        when(fixture.skillAccessPolicy.canBindWithProjectCredential(binding.skill(), "orders"))
                .thenReturn(true);
        when(fixture.skillCatalogService.detail(binding.skill().id()))
                .thenReturn(new SkillDetail(binding.skill(), List.of(binding.version())));

        Map<String, Object> result = fixture.service.listBindableSkills(7L, null);

        List<?> bindings = (List<?>) result.get("bindings");
        Map<?, ?> exposedBinding = (Map<?, ?>) bindings.get(0);
        Map<?, ?> exposedSkill = (Map<?, ?>) exposedBinding.get("skill");
        Map<?, ?> exposedVersion = (Map<?, ?>) exposedBinding.get("version");
        assertFalse(exposedSkill.containsKey("ownerUserId"));
        assertFalse(exposedVersion.containsKey("reviewedBy"));
        assertFalse(exposedVersion.containsKey("publishedBy"));
        assertEquals("DENY", result.get("scriptPolicy"));
    }

    private Fixture fixture() {
        CapabilityProjectOnboardingClient capabilityClient = mock(CapabilityProjectOnboardingClient.class);
        RuntimeProxyClient runtimeClient = mock(RuntimeProxyClient.class);
        ControlProjectAgentProvisioningService provisioningService = mock(ControlProjectAgentProvisioningService.class);
        AgentSkillCatalogService skillCatalogService = mock(AgentSkillCatalogService.class);
        AgentSkillAccessPolicy skillAccessPolicy = mock(AgentSkillAccessPolicy.class);
        AgentSkillBindingSnapshotAssembler skillSnapshotAssembler = mock(AgentSkillBindingSnapshotAssembler.class);
        ControlAiCodingAgentAuthoringService service = new ControlAiCodingAgentAuthoringService(
                capabilityClient, runtimeClient, provisioningService, skillCatalogService,
                skillAccessPolicy, skillSnapshotAssembler, new ObjectMapper());
        return new Fixture(service, capabilityClient, runtimeClient, provisioningService,
                skillCatalogService, skillAccessPolicy, skillSnapshotAssembler);
    }

    private Map<String, Object> project() {
        return Map.of("id", 7L, "projectCode", "orders", "name", "订单中心");
    }

    private Map<String, Object> agent() {
        return Map.of(
                "id", "agent-1", "projectId", 7L, "projectCode", "orders",
                "keySlug", "orders-helper", "name", "订单助手");
    }

    private BindingDescriptor binding() {
        LocalDateTime now = LocalDateTime.now();
        SkillSummary skill = new SkillSummary(
                11L, "reachai", "demo-skill", "示例 Skill", "说明", "PUBLIC",
                99L, null, "ACTIVE", 21L, 21L, 1L, now,
                "1.0.0", "PUBLISHED", "1.0.0");
        VersionView version = new VersionView(
                21L, 11L, "1.0.0", "PUBLISHED", "BUILTIN", "classpath",
                "a".repeat(64), "b".repeat(64), 256L, null, null,
                false, null, null, null, null, null,
                "reviewer", now, "publisher", now, now, now);
        return new BindingDescriptor(skill, version);
    }

    private record Fixture(
            ControlAiCodingAgentAuthoringService service,
            CapabilityProjectOnboardingClient capabilityClient,
            RuntimeProxyClient runtimeClient,
            ControlProjectAgentProvisioningService provisioningService,
            AgentSkillCatalogService skillCatalogService,
            AgentSkillAccessPolicy skillAccessPolicy,
            AgentSkillBindingSnapshotAssembler skillSnapshotAssembler) {
    }
}
