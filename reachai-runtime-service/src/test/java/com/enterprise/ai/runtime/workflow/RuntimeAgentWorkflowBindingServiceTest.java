package com.enterprise.ai.runtime.workflow;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeAgentWorkflowBindingServiceTest {

    @Test
    void createNormalizesDefaultsAndPersistsBinding() {
        RuntimeAgentWorkflowBindingMapper mapper = mock(RuntimeAgentWorkflowBindingMapper.class);
        RuntimeAgentWorkflowBindingService service = new RuntimeAgentWorkflowBindingService(mapper);
        RuntimeAgentWorkflowBindingView request = view(null, null, "wf-1", null, null, null);

        RuntimeAgentWorkflowBindingView created = service.create("agent-1", request);

        ArgumentCaptor<RuntimeAgentWorkflowBindingEntity> captor =
                ArgumentCaptor.forClass(RuntimeAgentWorkflowBindingEntity.class);
        verify(mapper).insert(captor.capture());
        RuntimeAgentWorkflowBindingEntity saved = captor.getValue();
        assertEquals("agent-1", saved.getAgentId());
        assertEquals("wf-1", saved.getWorkflowId());
        assertEquals("DEFAULT", saved.getBindingType());
        assertEquals(0, saved.getPriority());
        assertEquals(true, saved.getEnabled());
        assertEquals("agent-1", created.agentId());
    }

    @Test
    void updateRejectsMismatchedAgentAndMergesAllowedFields() {
        RuntimeAgentWorkflowBindingMapper mapper = mock(RuntimeAgentWorkflowBindingMapper.class);
        RuntimeAgentWorkflowBindingEntity existing = entity(9L, "agent-1", "wf-1", "DEFAULT", null, 0);
        when(mapper.selectById(9L)).thenReturn(existing);
        RuntimeAgentWorkflowBindingService service = new RuntimeAgentWorkflowBindingService(mapper);

        Optional<RuntimeAgentWorkflowBindingView> updated = service.update("agent-1", 9L,
                view(null, null, "wf-2", "PAGE", "orders.list", 3));

        assertTrue(updated.isPresent());
        assertEquals("wf-2", updated.get().workflowId());
        assertEquals("PAGE", existing.getBindingType());
        assertEquals("orders.list", existing.getPageKey());
        verify(mapper).updateById(existing);
    }

    @Test
    void listFindAndDeleteUseRuntimeBindingMapper() {
        RuntimeAgentWorkflowBindingMapper mapper = mock(RuntimeAgentWorkflowBindingMapper.class);
        RuntimeAgentWorkflowBindingEntity existing = entity(9L, "agent-1", "wf-1", "DEFAULT", null, 0);
        when(mapper.selectList(any())).thenReturn(List.of(existing));
        when(mapper.selectById(9L)).thenReturn(existing);
        when(mapper.deleteById(9L)).thenReturn(1);
        RuntimeAgentWorkflowBindingService service = new RuntimeAgentWorkflowBindingService(mapper);

        List<RuntimeAgentWorkflowBindingView> items = service.list("agent-1");
        Optional<RuntimeAgentWorkflowBindingView> found = service.findById(9L);
        boolean deleted = service.delete("agent-1", 9L);

        assertEquals(1, items.size());
        assertEquals("wf-1", found.orElseThrow().workflowId());
        assertEquals(true, deleted);
        verify(mapper).selectList(any());
        verify(mapper).deleteById(9L);
    }

    @Test
    void resolvePreviewPrefersMoreSpecificPageActionBinding() {
        RuntimeAgentWorkflowBindingMapper mapper = mock(RuntimeAgentWorkflowBindingMapper.class);
        when(mapper.selectList(org.mockito.ArgumentMatchers.<Wrapper<RuntimeAgentWorkflowBindingEntity>>any()))
                .thenReturn(List.of(
                        entity(1L, "agent-1", "default-flow", "DEFAULT", null, 100),
                        entity(2L, "agent-1", "page-flow", "PAGE", "orders.list", 20),
                        actionBinding(3L, "agent-1", "action-flow", "orders.list", "archive", 1)
                ));
        RuntimeAgentWorkflowBindingService service = new RuntimeAgentWorkflowBindingService(mapper);

        Optional<RuntimeAgentWorkflowBindingView> resolved = service.resolvePreview(
                new RuntimeAgentWorkflowBindingResolveRequest("agent-1", "orders", "orders.list",
                        "/orders", "archive", null));

        assertTrue(resolved.isPresent());
        assertEquals("action-flow", resolved.get().workflowId());
    }

    private RuntimeAgentWorkflowBindingView view(Long id,
                                                 String agentId,
                                                 String workflowId,
                                                 String bindingType,
                                                 String pageKey,
                                                 Integer priority) {
        return new RuntimeAgentWorkflowBindingView(
                id,
                agentId,
                workflowId,
                "orders",
                bindingType,
                pageKey,
                null,
                null,
                null,
                priority,
                null,
                null,
                null,
                null,
                null);
    }

    private RuntimeAgentWorkflowBindingEntity entity(Long id,
                                                     String agentId,
                                                     String workflowId,
                                                     String bindingType,
                                                     String pageKey,
                                                     Integer priority) {
        RuntimeAgentWorkflowBindingEntity entity = new RuntimeAgentWorkflowBindingEntity();
        entity.setId(id);
        entity.setAgentId(agentId);
        entity.setWorkflowId(workflowId);
        entity.setProjectCode("orders");
        entity.setBindingType(bindingType);
        entity.setPageKey(pageKey);
        entity.setPriority(priority);
        entity.setEnabled(true);
        entity.setUpdatedAt(LocalDateTime.parse("2026-06-29T10:00:00"));
        return entity;
    }

    private RuntimeAgentWorkflowBindingEntity actionBinding(Long id,
                                                            String agentId,
                                                            String workflowId,
                                                            String pageKey,
                                                            String actionKey,
                                                            Integer priority) {
        RuntimeAgentWorkflowBindingEntity entity = entity(id, agentId, workflowId, "ACTION", pageKey, priority);
        entity.setActionKey(actionKey);
        return entity;
    }
}
