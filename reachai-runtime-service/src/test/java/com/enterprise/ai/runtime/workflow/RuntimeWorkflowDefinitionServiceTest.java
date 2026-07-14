package com.enterprise.ai.runtime.workflow;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.enterprise.ai.runtime.agent.RuntimeAgentWorkflowToolEntity;
import com.enterprise.ai.runtime.agent.RuntimeAgentWorkflowToolMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeWorkflowDefinitionServiceTest {

    @Test
    void listFiltersWorkflowDefinitionsAndMarksDeletable() {
        RuntimeWorkflowDefinitionMapper mapper = mock(RuntimeWorkflowDefinitionMapper.class);
        RuntimeWorkflowVersionMapper versionMapper = mock(RuntimeWorkflowVersionMapper.class);
        RuntimeAgentWorkflowToolMapper workflowToolMapper = mock(RuntimeAgentWorkflowToolMapper.class);
        RuntimeWorkflowDefinitionService service = new RuntimeWorkflowDefinitionService(mapper, versionMapper, workflowToolMapper);
        RuntimeWorkflowDefinitionEntity draft = workflow("wf-1", "DRAFT");
        when(mapper.selectList(org.mockito.ArgumentMatchers.<Wrapper<RuntimeWorkflowDefinitionEntity>>any()))
                .thenReturn(List.of(draft));
        when(workflowToolMapper.selectList(org.mockito.ArgumentMatchers.<Wrapper<RuntimeAgentWorkflowToolEntity>>any()))
                .thenReturn(List.of());

        List<RuntimeWorkflowDefinitionEntity> result = service.list(7L, "orders", "CHAT", "DRAFT");

        assertEquals(List.of(draft), result);
        assertTrue(result.get(0).getDeletable());
        verify(mapper).selectList(org.mockito.ArgumentMatchers.<Wrapper<RuntimeWorkflowDefinitionEntity>>any());
        verify(workflowToolMapper).selectList(org.mockito.ArgumentMatchers.<Wrapper<RuntimeAgentWorkflowToolEntity>>any());
    }

    @Test
    void searchReturnsPaginatedKeywordResults() {
        RuntimeWorkflowDefinitionMapper mapper = mock(RuntimeWorkflowDefinitionMapper.class);
        RuntimeWorkflowDefinitionService service = new RuntimeWorkflowDefinitionService(
                mapper, mock(RuntimeWorkflowVersionMapper.class), mock(RuntimeAgentWorkflowToolMapper.class));
        RuntimeWorkflowDefinitionEntity active = workflow("wf-1", "ACTIVE");
        when(mapper.selectCount(org.mockito.ArgumentMatchers.<Wrapper<RuntimeWorkflowDefinitionEntity>>any()))
                .thenReturn(21L);
        when(mapper.selectList(org.mockito.ArgumentMatchers.<Wrapper<RuntimeWorkflowDefinitionEntity>>any()))
                .thenReturn(List.of(active));

        RuntimeWorkflowSearchPage result = service.search(
                null, null, null, "ACTIVE", "teams", 2, 10);

        assertEquals(List.of(active), result.records());
        assertEquals(21L, result.total());
        assertEquals(2, result.current());
        assertEquals(10, result.size());
        verify(mapper).selectCount(org.mockito.ArgumentMatchers.<Wrapper<RuntimeWorkflowDefinitionEntity>>any());
        verify(mapper).selectList(org.mockito.ArgumentMatchers.<Wrapper<RuntimeWorkflowDefinitionEntity>>any());
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
                mapper, mock(RuntimeWorkflowVersionMapper.class), mock(RuntimeAgentWorkflowToolMapper.class));
        when(mapper.selectById("wf-1")).thenReturn(workflow("wf-1", "PUBLISHED"));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service.delete("wf-1"));

        assertEquals("仅草稿状态的 Workflow 可删除", ex.getMessage());
    }

    @Test
    void deleteRejectsWorkflowThatIsStillAttachedAsAgentTool() {
        RuntimeWorkflowDefinitionMapper mapper = mock(RuntimeWorkflowDefinitionMapper.class);
        RuntimeAgentWorkflowToolMapper workflowToolMapper = mock(RuntimeAgentWorkflowToolMapper.class);
        RuntimeWorkflowDefinitionService service = new RuntimeWorkflowDefinitionService(
                mapper, mock(RuntimeWorkflowVersionMapper.class), workflowToolMapper);
        when(mapper.selectById("wf-1")).thenReturn(workflow("wf-1", "DRAFT"));
        RuntimeAgentWorkflowToolEntity workflowTool = new RuntimeAgentWorkflowToolEntity();
        workflowTool.setId(1L);
        workflowTool.setWorkflowId("wf-1");
        when(workflowToolMapper.selectList(org.mockito.ArgumentMatchers.<Wrapper<RuntimeAgentWorkflowToolEntity>>any()))
                .thenReturn(List.of(workflowTool));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service.delete("wf-1"));

        assertEquals("该 Workflow 仍被 Agent 配置为 Workflow-as-Tool，请先从 Agent 配置中移除后再删除", ex.getMessage());
    }

    @Test
    void deleteRemovesWorkflowVersionsBeforeDefinition() {
        RuntimeWorkflowDefinitionMapper mapper = mock(RuntimeWorkflowDefinitionMapper.class);
        RuntimeWorkflowVersionMapper versionMapper = mock(RuntimeWorkflowVersionMapper.class);
        RuntimeAgentWorkflowToolMapper workflowToolMapper = mock(RuntimeAgentWorkflowToolMapper.class);
        RuntimeWorkflowDefinitionService service = new RuntimeWorkflowDefinitionService(mapper, versionMapper, workflowToolMapper);
        when(mapper.selectById("wf-1")).thenReturn(workflow("wf-1", "DRAFT"));
        when(workflowToolMapper.selectList(org.mockito.ArgumentMatchers.<Wrapper<RuntimeAgentWorkflowToolEntity>>any()))
                .thenReturn(List.of());
        when(mapper.deleteById("wf-1")).thenReturn(1);

        service.delete("wf-1");

        verify(versionMapper).delete(any());
        verify(mapper).deleteById("wf-1");
    }

    @Test
    void isDeletableRequiresDraftAndNoWorkflowToolReference() {
        RuntimeWorkflowDefinitionMapper mapper = mock(RuntimeWorkflowDefinitionMapper.class);
        RuntimeAgentWorkflowToolMapper workflowToolMapper = mock(RuntimeAgentWorkflowToolMapper.class);
        RuntimeWorkflowDefinitionService service = new RuntimeWorkflowDefinitionService(
                mapper, mock(RuntimeWorkflowVersionMapper.class), workflowToolMapper);
        when(mapper.selectById("wf-1")).thenReturn(workflow("wf-1", "DRAFT"));
        when(mapper.selectById("wf-2")).thenReturn(workflow("wf-2", "PUBLISHED"));
        when(workflowToolMapper.selectList(org.mockito.ArgumentMatchers.<Wrapper<RuntimeAgentWorkflowToolEntity>>any()))
                .thenReturn(List.of());

        assertTrue(service.isDeletable("wf-1"));
        assertFalse(service.isDeletable("wf-2"));
    }

    @Test
    void updateWithBaseRevisionUsesAtomicCompareAndSwap() {
        RuntimeWorkflowDefinitionMapper mapper = mock(RuntimeWorkflowDefinitionMapper.class);
        RuntimeWorkflowDefinitionService service = new RuntimeWorkflowDefinitionService(
                mapper, mock(RuntimeWorkflowVersionMapper.class), mock(RuntimeAgentWorkflowToolMapper.class));
        RuntimeWorkflowDefinitionEntity current = workflow("wf-1", "DRAFT");
        current.setUpdatedAt(LocalDateTime.of(2026, 7, 14, 9, 30));
        when(mapper.selectById("wf-1")).thenReturn(current);
        when(mapper.update(any(RuntimeWorkflowDefinitionEntity.class),
                org.mockito.ArgumentMatchers.<Wrapper<RuntimeWorkflowDefinitionEntity>>any())).thenReturn(1);
        RuntimeWorkflowDefinitionEntity update = new RuntimeWorkflowDefinitionEntity();
        update.setName("Updated Orders");

        RuntimeWorkflowDefinitionEntity result = service.update(
                "wf-1", update, "2026-07-14T09:30");

        assertEquals("Updated Orders", result.getName());
        assertTrue(result.getUpdatedAt().isAfter(LocalDateTime.of(2026, 7, 14, 9, 30)));
        verify(mapper).update(any(RuntimeWorkflowDefinitionEntity.class),
                org.mockito.ArgumentMatchers.<Wrapper<RuntimeWorkflowDefinitionEntity>>any());
        verify(mapper, never()).updateById(any());
    }

    @Test
    void updateWithStaleBaseRevisionReturnsConflictBeforeWrite() {
        RuntimeWorkflowDefinitionMapper mapper = mock(RuntimeWorkflowDefinitionMapper.class);
        RuntimeWorkflowDefinitionService service = new RuntimeWorkflowDefinitionService(
                mapper, mock(RuntimeWorkflowVersionMapper.class), mock(RuntimeAgentWorkflowToolMapper.class));
        RuntimeWorkflowDefinitionEntity current = workflow("wf-1", "DRAFT");
        current.setUpdatedAt(LocalDateTime.of(2026, 7, 14, 9, 30));
        when(mapper.selectById("wf-1")).thenReturn(current);

        RuntimeWorkflowRevisionConflictException ex = assertThrows(
                RuntimeWorkflowRevisionConflictException.class,
                () -> service.update("wf-1", new RuntimeWorkflowDefinitionEntity(), "2026-07-14T09:29"));

        assertTrue(ex.getMessage().contains("currentRevision=2026-07-14T09:30"));
        verify(mapper, never()).updateById(any());
        verify(mapper, never()).update(any(RuntimeWorkflowDefinitionEntity.class),
                org.mockito.ArgumentMatchers.<Wrapper<RuntimeWorkflowDefinitionEntity>>any());
    }

    @Test
    void updateWithBaseRevisionDetectsConcurrentWriteAtDatabaseBoundary() {
        RuntimeWorkflowDefinitionMapper mapper = mock(RuntimeWorkflowDefinitionMapper.class);
        RuntimeWorkflowDefinitionService service = new RuntimeWorkflowDefinitionService(
                mapper, mock(RuntimeWorkflowVersionMapper.class), mock(RuntimeAgentWorkflowToolMapper.class));
        RuntimeWorkflowDefinitionEntity current = workflow("wf-1", "DRAFT");
        current.setUpdatedAt(LocalDateTime.of(2026, 7, 14, 9, 30));
        RuntimeWorkflowDefinitionEntity concurrent = workflow("wf-1", "DRAFT");
        concurrent.setUpdatedAt(LocalDateTime.of(2026, 7, 14, 9, 31));
        when(mapper.selectById("wf-1")).thenReturn(current, concurrent);
        when(mapper.update(any(RuntimeWorkflowDefinitionEntity.class),
                org.mockito.ArgumentMatchers.<Wrapper<RuntimeWorkflowDefinitionEntity>>any())).thenReturn(0);

        RuntimeWorkflowRevisionConflictException ex = assertThrows(
                RuntimeWorkflowRevisionConflictException.class,
                () -> service.update("wf-1", new RuntimeWorkflowDefinitionEntity(), "2026-07-14T09:30"));

        assertTrue(ex.getMessage().contains("currentRevision=2026-07-14T09:31"));
        verify(mapper).update(any(RuntimeWorkflowDefinitionEntity.class),
                org.mockito.ArgumentMatchers.<Wrapper<RuntimeWorkflowDefinitionEntity>>any());
    }

    private RuntimeWorkflowDefinitionService serviceWithMocks() {
        return new RuntimeWorkflowDefinitionService(
                mock(RuntimeWorkflowDefinitionMapper.class),
                mock(RuntimeWorkflowVersionMapper.class),
                mock(RuntimeAgentWorkflowToolMapper.class));
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
