package com.enterprise.ai.runtime.agent;

import com.enterprise.ai.runtime.agent.RuntimeAgentEntity;
import com.enterprise.ai.runtime.agent.RuntimeAgentMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeAgentServiceTest {

    @Test
    void createNormalizesDefaultsAndWritesRuntimeAgentTable() {
        RuntimeAgentMapper mapper = mock(RuntimeAgentMapper.class);
        RuntimeAgentConfigService configService = mock(RuntimeAgentConfigService.class);
        when(configService.resolveDisplayConfig(any())).thenReturn(Optional.empty());
        RuntimeAgentService service = new RuntimeAgentService(mapper, configService);
        RuntimeAgentIdentityRequest request = new RuntimeAgentIdentityRequest(
                null,
                7L,
                "orders",
                "orders-agent",
                "Orders Agent",
                null,
                null,
                null,
                null);

        RuntimeAgentView created = service.create(request);

        ArgumentCaptor<RuntimeAgentEntity> captor = ArgumentCaptor.forClass(RuntimeAgentEntity.class);
        verify(mapper).insert(captor.capture());
        RuntimeAgentEntity saved = captor.getValue();
        assertNotNull(saved.getId());
        assertEquals("PROJECT", saved.getVisibility());
        assertEquals(true, saved.getEnabled());
        assertEquals("orders-agent", created.keySlug());
        verify(configService, never()).createInitialDraft(saved.getId(), null, null, null);
    }

    @Test
    void updateMergesAllowedFields() {
        RuntimeAgentMapper mapper = mock(RuntimeAgentMapper.class);
        RuntimeAgentEntity existing = entity("agent-1");
        when(mapper.selectById("agent-1")).thenReturn(existing);
        RuntimeAgentConfigService configService = mock(RuntimeAgentConfigService.class);
        when(configService.resolveDisplayConfig(any())).thenReturn(Optional.empty());
        RuntimeAgentService service = new RuntimeAgentService(mapper, configService);

        RuntimeAgentView updated = service.update("agent-1", new RuntimeAgentIdentityRequest(
                null,
                null,
                null,
                null,
                "Orders Agent v2",
                "desc",
                null,
                null,
                false));

        assertEquals("Orders Agent v2", updated.name());
        assertEquals(false, existing.getEnabled());
        verify(mapper).updateById(existing);
        verify(configService, never()).saveDraft(org.mockito.ArgumentMatchers.eq("agent-1"), any());
    }

    @Test
    void findAndDeleteDelegateToRuntimeAgentMapper() {
        RuntimeAgentMapper mapper = mock(RuntimeAgentMapper.class);
        RuntimeAgentEntity existing = entity("agent-1");
        when(mapper.selectById("agent-1")).thenReturn(existing);
        when(mapper.deleteById("agent-1")).thenReturn(1);
        RuntimeAgentConfigService configService = mock(RuntimeAgentConfigService.class);
        when(configService.resolveDisplayConfig(any())).thenReturn(Optional.empty());
        RuntimeAgentService service = new RuntimeAgentService(mapper, configService);

        Optional<RuntimeAgentView> found = service.findById("agent-1");
        boolean deleted = service.delete("agent-1");

        assertTrue(found.isPresent());
        assertEquals("agent-1", found.get().id());
        assertEquals(true, deleted);
        verify(configService).deleteAllForAgent("agent-1");
    }

    @Test
    void findByIdOrKeySlugRetainsDisplayConfigResolution() {
        RuntimeAgentMapper mapper = mock(RuntimeAgentMapper.class);
        RuntimeAgentConfigService configService = mock(RuntimeAgentConfigService.class);
        RuntimeAgentEntity existing = entity("agent-1");
        when(mapper.selectById("orders-agent")).thenReturn(null);
        when(mapper.selectOne(any())).thenReturn(existing);
        when(configService.resolveDisplayConfig("agent-1")).thenReturn(Optional.empty());
        RuntimeAgentService service = new RuntimeAgentService(mapper, configService);

        Optional<RuntimeAgentView> found = service.findByIdOrKeySlug("orders-agent");

        assertTrue(found.isPresent());
        assertEquals("agent-1", found.get().id());
        verify(mapper).selectById("orders-agent");
        verify(mapper).selectOne(any());
        verify(configService).resolveDisplayConfig("agent-1");
    }

    @Test
    void listAppliesFiltersAndMapsResults() {
        RuntimeAgentMapper mapper = mock(RuntimeAgentMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of(entity("agent-1")));
        RuntimeAgentConfigService configService = mock(RuntimeAgentConfigService.class);
        when(configService.resolveDisplayConfig(any())).thenReturn(Optional.empty());
        RuntimeAgentService service = new RuntimeAgentService(mapper, configService);

        List<RuntimeAgentView> items = service.list(7L, "orders");

        assertEquals(1, items.size());
        assertEquals("agent-1", items.get(0).id());
        verify(mapper).selectList(any());
    }

    private RuntimeAgentEntity entity(String id) {
        RuntimeAgentEntity entity = new RuntimeAgentEntity();
        entity.setId(id);
        entity.setProjectId(7L);
        entity.setProjectCode("orders");
        entity.setKeySlug("orders-agent");
        entity.setName("Orders Agent");
        entity.setVisibility("PROJECT");
        entity.setEnabled(true);
        return entity;
    }
}
