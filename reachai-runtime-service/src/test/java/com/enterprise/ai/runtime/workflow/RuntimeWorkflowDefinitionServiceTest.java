package com.enterprise.ai.runtime.workflow;

import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDeletionReferences;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeWorkflowDefinitionServiceTest {

    @Test
    void deleteRejectsPublishedStateFromLockedRowEvenIfOrdinaryReadIsDraft() {
        var mapper = mock(RuntimeWorkflowDefinitionMapper.class);
        var stale = workflow("wf", "orders");
        stale.setStatus("DRAFT");
        var current = workflow("wf", "orders");
        current.setStatus("ACTIVE");
        when(mapper.selectById("wf")).thenReturn(stale);
        when(mapper.selectForRelease("wf")).thenReturn(current);
        var ex = assertThrows(IllegalArgumentException.class, () -> service(mapper).delete("wf"));
        assertEquals("仅草稿状态的 Workflow 可删除", ex.getMessage());
        verify(mapper, never()).deleteById("wf");
    }

    @Test
    void deleteChecksReferencesAfterLockBeforeRemovingOwnedData() {
        var mapper = mock(RuntimeWorkflowDefinitionMapper.class);
        var versions = mock(RuntimeWorkflowVersionMapper.class);
        var usage = mock(RuntimeWorkflowDeletionReferences.class);
        var resources = mock(RuntimeWorkflowResourceBindingService.class);
        var index = mock(RuntimeWorkflowReferenceIndex.class);
        var draft = workflow("wf", "orders");
        draft.setStatus("DRAFT");
        when(mapper.selectForRelease("wf")).thenReturn(draft);
        when(usage.referencedWorkflowIds(java.util.List.of("wf"))).thenReturn(java.util.Set.of("wf"));
        var service = new RuntimeWorkflowDefinitionService(mapper, versions, usage,
                new RuntimeWorkflowDocumentCanonicalizer(new ObjectMapper()), resources, index);
        var ex = assertThrows(IllegalArgumentException.class, () -> service.delete(" wf "));
        org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains("Workflow-as-Tool"));
        var order = org.mockito.Mockito.inOrder(mapper, usage);
        order.verify(mapper).selectForRelease("wf");
        order.verify(usage).referencedWorkflowIds(java.util.List.of("wf"));
        org.mockito.Mockito.verifyNoInteractions(versions, resources, index);
        verify(mapper, never()).deleteById("wf");
    }

    @Test
    void createPersistsCanonicalWorkflowSemantics() {
        RuntimeWorkflowDefinitionMapper mapper = mock(RuntimeWorkflowDefinitionMapper.class);
        when(mapper.selectOne(any())).thenReturn(null);
        RuntimeWorkflowDefinitionService service = service(mapper);

        RuntimeWorkflowDefinitionEntity created = service.create(workflow(null, "orders"));

        assertEquals("GENERAL", created.getWorkflowKind());
        assertEquals("GRAPH_SPEC", created.getExecutionEngine());
        assertEquals("USER", created.getDefinitionAuthority());
        assertEquals("STUDIO", created.getCreationChannel());
    }

    @Test
    void createAcceptsExplicitCanonicalSdkSemantics() {
        RuntimeWorkflowDefinitionMapper mapper = mock(RuntimeWorkflowDefinitionMapper.class);
        when(mapper.selectOne(any())).thenReturn(null);
        RuntimeWorkflowDefinitionService service = service(mapper);
        RuntimeWorkflowDefinitionEntity draft = workflow(null, "sdk-orders");
        draft.setWorkflowKind("GENERAL");
        draft.setExecutionEngine("GRAPH_SPEC");
        draft.setDefinitionAuthority("SDK");
        draft.setCreationChannel("SDK_SYNC");

        RuntimeWorkflowDefinitionEntity created = service.create(draft);

        assertEquals("GENERAL", created.getWorkflowKind());
        assertEquals("GRAPH_SPEC", created.getExecutionEngine());
        assertEquals("SDK", created.getDefinitionAuthority());
        assertEquals("SDK_SYNC", created.getCreationChannel());
    }

    @Test
    void createRejectsExistingKeyBeforeInsert() {
        RuntimeWorkflowDefinitionMapper mapper = mock(RuntimeWorkflowDefinitionMapper.class);
        RuntimeWorkflowDefinitionEntity existing = workflow("existing", "1111");
        when(mapper.selectOne(any())).thenReturn(existing);
        RuntimeWorkflowDefinitionService service = service(mapper);

        RuntimeWorkflowKeySlugConflictException ex = assertThrows(
                RuntimeWorkflowKeySlugConflictException.class,
                () -> service.create(workflow(null, "1111")));

        org.junit.jupiter.api.Assertions.assertEquals("1111", ex.getKeySlug());
        verify(mapper, never()).insert(any());
    }

    @Test
    void createMapsConcurrentDuplicateInsertToKeyConflict() {
        RuntimeWorkflowDefinitionMapper mapper = mock(RuntimeWorkflowDefinitionMapper.class);
        when(mapper.selectOne(any())).thenReturn(null);
        when(mapper.insert(any())).thenThrow(new DuplicateKeyException("duplicate"));
        RuntimeWorkflowDefinitionService service = service(mapper);

        assertThrows(RuntimeWorkflowKeySlugConflictException.class,
                () -> service.create(workflow(null, "1111")));
    }

    private RuntimeWorkflowDefinitionService service(RuntimeWorkflowDefinitionMapper mapper) {
        return new RuntimeWorkflowDefinitionService(
                mapper,
                mock(RuntimeWorkflowVersionMapper.class),
                mock(RuntimeWorkflowDeletionReferences.class),
                new RuntimeWorkflowDocumentCanonicalizer(new ObjectMapper()),
                mock(RuntimeWorkflowResourceBindingService.class), mock(RuntimeWorkflowReferenceIndex.class));
    }

    private RuntimeWorkflowDefinitionEntity workflow(String id, String keySlug) {
        RuntimeWorkflowDefinitionEntity workflow = new RuntimeWorkflowDefinitionEntity();
        workflow.setId(id);
        workflow.setKeySlug(keySlug);
        workflow.setName("test");
        return workflow;
    }
}
