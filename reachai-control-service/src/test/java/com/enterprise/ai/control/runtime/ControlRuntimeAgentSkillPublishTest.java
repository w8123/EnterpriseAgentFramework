package com.enterprise.ai.control.runtime;

import com.enterprise.ai.control.agentskill.AgentSkillBindingSnapshotAssembler;
import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ControlRuntimeAgentSkillPublishTest {

    @Test
    void revalidatesDraftSkillVersionsAndAgentProjectBeforePublishing() {
        RuntimeProxyClient runtime = mock(RuntimeProxyClient.class);
        AgentSkillBindingSnapshotAssembler assembler = mock(AgentSkillBindingSnapshotAssembler.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        ControlRuntimePublicController controller = new ControlRuntimePublicController(
                runtime, null, null, null, null, null, assembler);

        Map<String, Object> binding = Map.of("skillId", 11L, "skillVersionId", 21L);
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("id", 31L);
        config.put("status", "DRAFT");
        config.put("skills", List.of(binding));
        when(runtime.listAgentConfigVersions("agent-1"))
                .thenReturn(ResponseEntity.ok(List.of(config)));
        when(runtime.getAgent("agent-1"))
                .thenReturn(ResponseEntity.ok(Map.of("id", "agent-1", "projectCode", "finance-core")));
        when(assembler.assemble(eq(request), eq(config), eq("finance-core"))).thenReturn(config);
        when(runtime.publishAgentConfigVersion("agent-1", 31L, Map.of("publishedBy", "alice")))
                .thenReturn(ResponseEntity.ok(Map.of("id", 31L, "status", "ACTIVE")));

        ResponseEntity<Object> response = controller.publishAgentConfigVersion(
                request, "agent-1", 31L, Map.of("publishedBy", "alice"));

        assertEquals(200, response.getStatusCode().value());
        var ordered = inOrder(runtime, assembler);
        ordered.verify(runtime).listAgentConfigVersions("agent-1");
        ordered.verify(runtime).getAgent("agent-1");
        ordered.verify(assembler).assemble(request, config, "finance-core");
        ordered.verify(runtime).publishAgentConfigVersion(
                "agent-1", 31L, Map.of("publishedBy", "alice"));
    }

    @Test
    void refusesToPublishWhenPersistedSkillScopeDiffersFromControlAttestation() {
        RuntimeProxyClient runtime = mock(RuntimeProxyClient.class);
        AgentSkillBindingSnapshotAssembler assembler = mock(AgentSkillBindingSnapshotAssembler.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        ControlRuntimePublicController controller = new ControlRuntimePublicController(
                runtime, null, null, null, null, null, assembler);

        Map<String, Object> persistedBinding = new LinkedHashMap<>();
        persistedBinding.put("skillId", 11L);
        persistedBinding.put("skillVersionId", 21L);
        persistedBinding.put("visibility", "PUBLIC");
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("id", 31L);
        config.put("skills", List.of(persistedBinding));

        Map<String, Object> canonicalBinding = new LinkedHashMap<>(persistedBinding);
        canonicalBinding.put("visibility", "PROJECT");
        canonicalBinding.put("projectCode", "finance-core");
        Map<String, Object> canonical = new LinkedHashMap<>(config);
        canonical.put("skills", List.of(canonicalBinding));

        when(runtime.listAgentConfigVersions("agent-1"))
                .thenReturn(ResponseEntity.ok(List.of(config)));
        when(runtime.getAgent("agent-1"))
                .thenReturn(ResponseEntity.ok(Map.of("id", "agent-1", "projectCode", "finance-core")));
        when(assembler.assemble(eq(request), eq(config), eq("finance-core"))).thenReturn(canonical);

        var failure = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> controller.publishAgentConfigVersion(request, "agent-1", 31L, Map.of()));

        assertEquals(409, failure.getStatusCode().value());
        verify(runtime, never()).publishAgentConfigVersion(
                eq("agent-1"), eq(31L), org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    void refusesToMoveAgentAfterProjectScopedSkillWasBound() {
        RuntimeProxyClient runtime = mock(RuntimeProxyClient.class);
        AgentSkillBindingSnapshotAssembler assembler = mock(AgentSkillBindingSnapshotAssembler.class);
        ControlRuntimePublicController controller = new ControlRuntimePublicController(
                runtime, null, null, null, null, null, assembler);
        when(runtime.getAgent("agent-1")).thenReturn(ResponseEntity.ok(Map.of(
                "id", "agent-1", "projectId", 10L, "projectCode", "finance-core")));
        when(runtime.listAgentConfigVersions("agent-1")).thenReturn(ResponseEntity.ok(List.of(Map.of(
                "id", 31L,
                "status", "ACTIVE",
                "skills", List.of(Map.of(
                        "skillId", 11L,
                        "visibility", "PROJECT",
                        "projectCode", "finance-core"))))));

        var failure = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> controller.updateAgent("agent-1", Map.of(
                        "projectId", 20L, "projectCode", "hr-core")));

        assertEquals(409, failure.getStatusCode().value());
        verify(runtime, never()).updateAgent(eq("agent-1"), org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    void refusesProjectIdChangeEvenWhenCallerRepeatsTheOldProjectCode() {
        RuntimeProxyClient runtime = mock(RuntimeProxyClient.class);
        AgentSkillBindingSnapshotAssembler assembler = mock(AgentSkillBindingSnapshotAssembler.class);
        ControlRuntimePublicController controller = new ControlRuntimePublicController(
                runtime, null, null, null, null, null, assembler);
        when(runtime.getAgent("agent-1")).thenReturn(ResponseEntity.ok(Map.of(
                "id", "agent-1", "projectId", 10L, "projectCode", "finance-core")));
        when(runtime.listAgentConfigVersions("agent-1")).thenReturn(ResponseEntity.ok(List.of(Map.of(
                "id", 31L,
                "skills", List.of(Map.of("visibility", "PROJECT", "projectCode", "finance-core"))))));

        var failure = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> controller.updateAgent("agent-1", Map.of(
                        "projectId", 20L, "projectCode", "finance-core")));

        assertEquals(409, failure.getStatusCode().value());
        verify(runtime, never()).updateAgent(eq("agent-1"), org.mockito.ArgumentMatchers.anyMap());
    }
}
