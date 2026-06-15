package com.enterprise.ai.agent.workflow;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.ai.agent.workflow.WorkflowReleaseValidationResult;
import com.enterprise.ai.agent.identity.PageActionRegistryEntity;
import com.enterprise.ai.agent.identity.PageActionRegistryMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkflowReleaseValidationServiceTest {

    @Test
    void missingGraphSpecFailsWithoutAgentDefinition() {
        WorkflowReleaseValidationService service = new WorkflowReleaseValidationService(mock(PageActionRegistryMapper.class), new ObjectMapper());
        WorkflowDefinitionEntity workflow = workflow(null);

        WorkflowReleaseValidationResult result = service.validate(workflow);

        assertFalse(result.valid());
        assertTrue(hasError(result, "GRAPH_SPEC_MISSING"));
    }

    @Test
    void missingEntryNodeFails() {
        WorkflowReleaseValidationService service = new WorkflowReleaseValidationService(mock(PageActionRegistryMapper.class), new ObjectMapper());
        WorkflowDefinitionEntity workflow = workflow("""
                {"nodes":[{"id":"answer","type":"ANSWER"}],"edges":[]}
                """);

        WorkflowReleaseValidationResult result = service.validate(workflow);

        assertFalse(result.valid());
        assertTrue(hasError(result, "GRAPH_ENTRY_MISSING"));
    }

    @Test
    void pageActionRequiresPageKeyAndActionKey() {
        WorkflowReleaseValidationService service = new WorkflowReleaseValidationService(mock(PageActionRegistryMapper.class), new ObjectMapper());
        WorkflowDefinitionEntity workflow = workflow("""
                {
                  "nodes":[{"id":"open","type":"PAGE_ACTION","config":{}}],
                  "edges":[],
                  "entry":"open",
                  "finish":["open"]
                }
                """);

        WorkflowReleaseValidationResult result = service.validate(workflow);

        assertFalse(result.valid());
        assertTrue(hasError(result, "GRAPH_PAGE_ACTION_PAGE_KEY_EMPTY"));
        assertTrue(hasError(result, "GRAPH_PAGE_ACTION_KEY_EMPTY"));
    }

    @Test
    void pageActionValidatesAgainstCatalog() {
        PageActionRegistryMapper mapper = mock(PageActionRegistryMapper.class);
        WorkflowReleaseValidationService service = new WorkflowReleaseValidationService(mapper, new ObjectMapper());
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(activePageAction());
        WorkflowDefinitionEntity workflow = workflow("""
                {
                  "nodes":[{"id":"open","type":"PAGE_ACTION","config":{"projectCode":"demo","pageKey":"orders","actionKey":"open"}}],
                  "edges":[],
                  "entry":"open",
                  "finish":["open"]
                }
                """);

        WorkflowReleaseValidationResult result = service.validate(workflow);

        assertTrue(result.valid());
    }

    @Test
    void missingPageActionCatalogFails() {
        PageActionRegistryMapper mapper = mock(PageActionRegistryMapper.class);
        WorkflowReleaseValidationService service = new WorkflowReleaseValidationService(mapper, new ObjectMapper());
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        WorkflowDefinitionEntity workflow = workflow("""
                {
                  "nodes":[{"id":"open","type":"PAGE_ACTION","config":{"projectCode":"demo","pageKey":"orders","actionKey":"open"}}],
                  "edges":[],
                  "entry":"open",
                  "finish":["open"]
                }
                """);

        WorkflowReleaseValidationResult result = service.validate(workflow);

        assertFalse(result.valid());
        assertTrue(hasError(result, "GRAPH_PAGE_ACTION_CATALOG_MISSING"));
    }

    private boolean hasError(WorkflowReleaseValidationResult result, String code) {
        return result.errors().stream().anyMatch(item -> code.equals(item.code()));
    }

    private WorkflowDefinitionEntity workflow(String graphSpecJson) {
        WorkflowDefinitionEntity entity = new WorkflowDefinitionEntity();
        entity.setId("wf-1");
        entity.setProjectCode("demo");
        entity.setKeySlug("orders");
        entity.setName("Orders");
        entity.setRuntimeType("LANGGRAPH4J");
        entity.setGraphSpecJson(graphSpecJson);
        return entity;
    }

    private PageActionRegistryEntity activePageAction() {
        PageActionRegistryEntity action = new PageActionRegistryEntity();
        action.setProjectCode("demo");
        action.setPageKey("orders");
        action.setActionKey("open");
        action.setStatus("ACTIVE");
        return action;
    }
}
