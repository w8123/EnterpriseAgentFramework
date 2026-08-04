package com.enterprise.ai.runtime.workflow;

import com.enterprise.ai.runtime.agent.RuntimeAgentWorkflowToolMapper;
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
                mock(RuntimeAgentWorkflowToolMapper.class),
                new RuntimeWorkflowDocumentCanonicalizer(new ObjectMapper()),
                mock(RuntimeWorkflowResourceBindingService.class));
    }

    private RuntimeWorkflowDefinitionEntity workflow(String id, String keySlug) {
        RuntimeWorkflowDefinitionEntity workflow = new RuntimeWorkflowDefinitionEntity();
        workflow.setId(id);
        workflow.setKeySlug(keySlug);
        workflow.setName("test");
        return workflow;
    }
}
