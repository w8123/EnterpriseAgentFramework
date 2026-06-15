package com.enterprise.ai.agent.workflow;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowDefinitionServiceTest {

    @Test
    void createFillsDefaultsAndPersistsGraphSpec() {
        WorkflowDefinitionMapper mapper = mock(WorkflowDefinitionMapper.class);
        when(mapper.insert(any(WorkflowDefinitionEntity.class))).thenReturn(1);
        WorkflowDefinitionService service = new WorkflowDefinitionService(mapper);

        WorkflowDefinitionEntity created = service.create(workflow("page-search", "{\"nodes\":[]}"));

        assertEquals("CHAT", created.getWorkflowType());
        assertEquals("LANGGRAPH4J", created.getRuntimeType());
        assertEquals("DRAFT", created.getStatus());
        assertEquals("MANUAL", created.getManagedBy());
        assertEquals(12, created.getId().length());

        ArgumentCaptor<WorkflowDefinitionEntity> captor = ArgumentCaptor.forClass(WorkflowDefinitionEntity.class);
        verify(mapper).insert(captor.capture());
        assertTrue(captor.getValue().getGraphSpecJson().contains("nodes"));
    }

    private WorkflowDefinitionEntity workflow(String keySlug, String graphSpecJson) {
        WorkflowDefinitionEntity entity = new WorkflowDefinitionEntity();
        entity.setProjectId(101L);
        entity.setProjectCode("demo");
        entity.setKeySlug(keySlug);
        entity.setName("Page Search");
        entity.setGraphSpecJson(graphSpecJson);
        return entity;
    }
}
