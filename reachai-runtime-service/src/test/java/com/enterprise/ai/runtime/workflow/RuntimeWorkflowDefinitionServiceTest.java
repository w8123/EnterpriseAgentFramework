package com.enterprise.ai.runtime.workflow;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeWorkflowDefinitionServiceTest {

    @Test
    void listFiltersWorkflowDefinitionsAndMarksDeletable() {
        RuntimeWorkflowDefinitionMapper mapper = mock(RuntimeWorkflowDefinitionMapper.class);
        RuntimeWorkflowVersionMapper versionMapper = mock(RuntimeWorkflowVersionMapper.class);
        RuntimeAgentWorkflowBindingMapper bindingMapper = mock(RuntimeAgentWorkflowBindingMapper.class);
        RuntimeWorkflowDefinitionService service = new RuntimeWorkflowDefinitionService(mapper, versionMapper, bindingMapper);
        RuntimeWorkflowDefinitionEntity draft = workflow("wf-1", "DRAFT");
        when(mapper.selectList(org.mockito.ArgumentMatchers.<Wrapper<RuntimeWorkflowDefinitionEntity>>any()))
                .thenReturn(List.of(draft));
        when(bindingMapper.selectList(org.mockito.ArgumentMatchers.<Wrapper<RuntimeAgentWorkflowBindingEntity>>any()))
                .thenReturn(List.of());

        List<RuntimeWorkflowDefinitionEntity> result = service.list(7L, "orders", "CHAT", "DRAFT");

        assertEquals(List.of(draft), result);
        assertTrue(result.get(0).getDeletable());
        verify(mapper).selectList(org.mockito.ArgumentMatchers.<Wrapper<RuntimeWorkflowDefinitionEntity>>any());
        verify(bindingMapper).selectList(org.mockito.ArgumentMatchers.<Wrapper<RuntimeAgentWorkflowBindingEntity>>any());
    }

    @Test
    void findByIdReturnsEmptyWhenIdIsBlank() {
        RuntimeWorkflowDefinitionService service = serviceWithMocks();

        Optional<RuntimeWorkflowDefinitionEntity> result = service.findById(" ");

        assertTrue(result.isEmpty());
    }

    @Test
    void deleteRejectsPublishedWorkflow() {
        RuntimeWorkflowDefinitionMapper mapper = mock(RuntimeWorkflowDefinitionMapper.class);
        RuntimeWorkflowDefinitionService service = new RuntimeWorkflowDefinitionService(
                mapper, mock(RuntimeWorkflowVersionMapper.class), mock(RuntimeAgentWorkflowBindingMapper.class));
        when(mapper.selectById("wf-1")).thenReturn(workflow("wf-1", "PUBLISHED"));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service.delete("wf-1"));

        assertEquals("仅草稿状态的 Workflow 可删除", ex.getMessage());
    }

    @Test
    void deleteRejectsWorkflowThatIsStillBoundByAgent() {
        RuntimeWorkflowDefinitionMapper mapper = mock(RuntimeWorkflowDefinitionMapper.class);
        RuntimeAgentWorkflowBindingMapper bindingMapper = mock(RuntimeAgentWorkflowBindingMapper.class);
        RuntimeWorkflowDefinitionService service = new RuntimeWorkflowDefinitionService(
                mapper, mock(RuntimeWorkflowVersionMapper.class), bindingMapper);
        when(mapper.selectById("wf-1")).thenReturn(workflow("wf-1", "DRAFT"));
        RuntimeAgentWorkflowBindingEntity binding = new RuntimeAgentWorkflowBindingEntity();
        binding.setId(1L);
        binding.setWorkflowId("wf-1");
        when(bindingMapper.selectList(org.mockito.ArgumentMatchers.<Wrapper<RuntimeAgentWorkflowBindingEntity>>any()))
                .thenReturn(List.of(binding));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service.delete("wf-1"));

        assertEquals("该 Workflow 仍被 Agent 绑定，请先解除绑定后再删除", ex.getMessage());
    }

    @Test
    void deleteRemovesWorkflowVersionsBeforeDefinition() {
        RuntimeWorkflowDefinitionMapper mapper = mock(RuntimeWorkflowDefinitionMapper.class);
        RuntimeWorkflowVersionMapper versionMapper = mock(RuntimeWorkflowVersionMapper.class);
        RuntimeAgentWorkflowBindingMapper bindingMapper = mock(RuntimeAgentWorkflowBindingMapper.class);
        RuntimeWorkflowDefinitionService service = new RuntimeWorkflowDefinitionService(mapper, versionMapper, bindingMapper);
        when(mapper.selectById("wf-1")).thenReturn(workflow("wf-1", "DRAFT"));
        when(bindingMapper.selectList(org.mockito.ArgumentMatchers.<Wrapper<RuntimeAgentWorkflowBindingEntity>>any()))
                .thenReturn(List.of());
        when(mapper.deleteById("wf-1")).thenReturn(1);

        service.delete("wf-1");

        verify(versionMapper).delete(any());
        verify(mapper).deleteById("wf-1");
    }

    @Test
    void isDeletableRequiresDraftAndNoBinding() {
        RuntimeWorkflowDefinitionMapper mapper = mock(RuntimeWorkflowDefinitionMapper.class);
        RuntimeAgentWorkflowBindingMapper bindingMapper = mock(RuntimeAgentWorkflowBindingMapper.class);
        RuntimeWorkflowDefinitionService service = new RuntimeWorkflowDefinitionService(
                mapper, mock(RuntimeWorkflowVersionMapper.class), bindingMapper);
        when(mapper.selectById("wf-1")).thenReturn(workflow("wf-1", "DRAFT"));
        when(mapper.selectById("wf-2")).thenReturn(workflow("wf-2", "PUBLISHED"));
        when(bindingMapper.selectList(org.mockito.ArgumentMatchers.<Wrapper<RuntimeAgentWorkflowBindingEntity>>any()))
                .thenReturn(List.of());

        assertTrue(service.isDeletable("wf-1"));
        assertFalse(service.isDeletable("wf-2"));
    }

    private RuntimeWorkflowDefinitionService serviceWithMocks() {
        return new RuntimeWorkflowDefinitionService(
                mock(RuntimeWorkflowDefinitionMapper.class),
                mock(RuntimeWorkflowVersionMapper.class),
                mock(RuntimeAgentWorkflowBindingMapper.class));
    }

    private RuntimeWorkflowDefinitionEntity workflow(String id, String status) {
        RuntimeWorkflowDefinitionEntity entity = new RuntimeWorkflowDefinitionEntity();
        entity.setId(id);
        entity.setKeySlug("orders");
        entity.setName("Orders");
        entity.setStatus(status);
        return entity;
    }
}
