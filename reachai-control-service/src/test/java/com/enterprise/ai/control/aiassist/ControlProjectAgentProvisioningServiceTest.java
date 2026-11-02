package com.enterprise.ai.control.aiassist;

import com.enterprise.ai.control.client.capability.CapabilityProjectOnboardingClient;
import com.enterprise.ai.control.client.model.ControlModelCatalogClient;
import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ControlProjectAgentProvisioningServiceTest {
    private final CapabilityProjectOnboardingClient projects = mock(CapabilityProjectOnboardingClient.class);
    private final RuntimeProxyClient runtime = mock(RuntimeProxyClient.class);
    private final ControlModelCatalogClient models = mock(ControlModelCatalogClient.class);
    private final ControlProjectAgentProvisioningDraftGateway gateway = mock(ControlProjectAgentProvisioningDraftGateway.class);
    private final ControlProjectAgentProvisioningDraftGateway.Drafts drafts = mock(ControlProjectAgentProvisioningDraftGateway.Drafts.class);
    private final ControlProjectAgentProvisioningService service = new ControlProjectAgentProvisioningService(projects, runtime, models, gateway);
    @BeforeEach void setUp() {
        when(projects.getOnboardingProjectById(7L)).thenReturn(Map.of("id", 7L, "projectCode", "orders", "name", "Orders"));
        when(gateway.open(7L, "orders")).thenReturn(drafts);
        when(models.list(null, "LLM", null)).thenReturn(ResponseEntity.ok(Map.of("data", List.of(Map.of("id", "model-1", "status", "ACTIVE")))));
        when(runtime.listAgents(7L, "orders")).thenReturn(ResponseEntity.ok(List.of(Map.of(
                "id", "agent-1", "projectId", 7L, "projectCode", "orders", "keySlug", "orders-page-copilot"))));
    }
    @Test void existingUnpublishedDraftCannotBeOverwrittenOrPublishedByProvisioning() {
        when(drafts.context("agent-1")).thenReturn(context(Map.of("id", 21L, "status", "DRAFT"), "a".repeat(64)));
        when(drafts.versions("agent-1")).thenReturn(Map.of("versions", List.of()));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> service.provision(7L, Map.of())).getMessage().contains("unpublished draft"));
        verify(drafts).context("agent-1"); verify(drafts).versions("agent-1"); verifyNoMoreInteractions(drafts);
        verify(runtime, never()).saveAgentConfigDraft(anyString(), anyMap());
        verify(runtime, never()).publishAgentConfigVersion(anyString(), anyLong(), anyMap());
    }
    @Test void activeConfigIsReusedEvenWhenAnotherDraftExists() {
        var active = Map.<String, Object>of("id", 20L, "agentId", "agent-1", "status", "ACTIVE", "systemPrompt", "custom");
        when(drafts.context("agent-1")).thenReturn(context(Map.of("id", 21L, "status", "DRAFT"), "a".repeat(64)));
        when(drafts.versions("agent-1")).thenReturn(Map.of("versions", List.of(active)));
        assertEquals(active, service.provision(7L, Map.of()).get("supervisorConfig"));
        verify(drafts).context("agent-1"); verify(drafts).versions("agent-1"); verifyNoMoreInteractions(drafts);
    }
    @Test void concurrentEditConflictCannotRefreshRevisionAndRetryInitialization() {
        when(drafts.context("agent-1")).thenReturn(context(null, "a".repeat(64)));
        when(drafts.save(eq("agent-1"), eq("a".repeat(64)), anyMap())).thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT));
        assertThrows(ResponseStatusException.class, () -> service.provision(7L, Map.of("requestedBy", "forged-human")));
        verify(drafts, times(1)).context("agent-1"); verify(drafts, times(1)).save(eq("agent-1"), eq("a".repeat(64)), anyMap());
        verifyNoMoreInteractions(drafts);
    }
    @Test void deniedIdentityCannotCreateAgentOrReadModelCatalog() {
        when(gateway.open(7L, "orders")).thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN));
        assertThrows(ResponseStatusException.class, () -> service.provision(7L, Map.of())); verifyNoInteractions(runtime, models);
    }
    @Test void wrongProjectFromCapabilityCannotReachRuntime() {
        when(projects.getOnboardingProjectById(7L)).thenReturn(Map.of("id", 8L, "projectCode", "orders"));
        assertThrows(ResponseStatusException.class, () -> service.provision(7L, Map.of())); verifyNoInteractions(runtime, models, gateway);
    }
    @Test void foreignAgentInRuntimeListCannotReachDrafts() {
        when(runtime.listAgents(7L, "orders")).thenReturn(ResponseEntity.ok(List.of(Map.of(
                "id", "agent-foreign", "projectId", 8L, "projectCode", "billing", "keySlug", "orders-page-copilot"))));
        assertThrows(ResponseStatusException.class, () -> service.provision(7L, Map.of())); verifyNoInteractions(drafts);
    }
    private Map<String, Object> context(Object base, String revision) {
        var result = new java.util.LinkedHashMap<String, Object>(); result.put("base", base); result.put("revision", revision); return result;
    }
}
