package com.enterprise.ai.runtime.agent;

import com.enterprise.ai.runtime.workflow.RuntimeAgentEntryEntity;
import com.enterprise.ai.runtime.workflow.RuntimeAgentEntryMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeAgentEntryServiceTest {

    @Test
    void createNormalizesDefaultsAndWritesRuntimeAgentTable() {
        RuntimeAgentEntryMapper mapper = mock(RuntimeAgentEntryMapper.class);
        RuntimeAgentEntryService service = new RuntimeAgentEntryService(mapper);
        RuntimeAgentEntryView request = new RuntimeAgentEntryView(
                null,
                7L,
                "orders",
                "orders-agent",
                "Orders Agent",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);

        RuntimeAgentEntryView created = service.create(request);

        ArgumentCaptor<RuntimeAgentEntryEntity> captor = ArgumentCaptor.forClass(RuntimeAgentEntryEntity.class);
        verify(mapper).insert(captor.capture());
        RuntimeAgentEntryEntity saved = captor.getValue();
        assertNotNull(saved.getId());
        assertEquals("PROJECT_ENTRY", saved.getAgentKind());
        assertEquals("PROJECT", saved.getVisibility());
        assertEquals(true, saved.getEnabled());
        assertEquals("orders-agent", created.keySlug());
    }

    @Test
    void updateMergesAllowedFields() {
        RuntimeAgentEntryMapper mapper = mock(RuntimeAgentEntryMapper.class);
        RuntimeAgentEntryEntity existing = entity("agent-1");
        when(mapper.selectById("agent-1")).thenReturn(existing);
        RuntimeAgentEntryService service = new RuntimeAgentEntryService(mapper);

        RuntimeAgentEntryView updated = service.update("agent-1", new RuntimeAgentEntryView(
                null,
                null,
                null,
                null,
                "Orders Agent v2",
                "desc",
                "PAGE_COPILOT",
                null,
                "prompt",
                null,
                null,
                null,
                false,
                null,
                null));

        assertEquals("Orders Agent v2", updated.name());
        assertEquals("PAGE_COPILOT", existing.getAgentKind());
        assertEquals(false, existing.getEnabled());
        verify(mapper).updateById(existing);
    }

    @Test
    void findAndDeleteDelegateToRuntimeAgentMapper() {
        RuntimeAgentEntryMapper mapper = mock(RuntimeAgentEntryMapper.class);
        RuntimeAgentEntryEntity existing = entity("agent-1");
        when(mapper.selectById("agent-1")).thenReturn(existing);
        when(mapper.deleteById("agent-1")).thenReturn(1);
        RuntimeAgentEntryService service = new RuntimeAgentEntryService(mapper);

        Optional<RuntimeAgentEntryView> found = service.findById("agent-1");
        boolean deleted = service.delete("agent-1");

        assertTrue(found.isPresent());
        assertEquals("agent-1", found.get().id());
        assertEquals(true, deleted);
    }

    @Test
    void listAppliesFiltersAndMapsResults() {
        RuntimeAgentEntryMapper mapper = mock(RuntimeAgentEntryMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of(entity("agent-1")));
        RuntimeAgentEntryService service = new RuntimeAgentEntryService(mapper);

        List<RuntimeAgentEntryView> items = service.list(7L, "orders", "PAGE_COPILOT");

        assertEquals(1, items.size());
        assertEquals("agent-1", items.get(0).id());
        verify(mapper).selectList(any());
    }

    private RuntimeAgentEntryEntity entity(String id) {
        RuntimeAgentEntryEntity entity = new RuntimeAgentEntryEntity();
        entity.setId(id);
        entity.setProjectId(7L);
        entity.setProjectCode("orders");
        entity.setKeySlug("orders-agent");
        entity.setName("Orders Agent");
        entity.setAgentKind("PROJECT_ENTRY");
        entity.setVisibility("PROJECT");
        entity.setEnabled(true);
        return entity;
    }
}
