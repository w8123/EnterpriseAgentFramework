package com.enterprise.ai.runtime.workflow;

import com.enterprise.ai.runtime.client.control.RuntimeControlCatalogClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuntimeWorkflowReleaseValidationServiceTest {

    @Test
    void missingGraphSpecFails() {
        RuntimeWorkflowReleaseValidationService service = service(mock(RuntimeControlCatalogClient.class));
        RuntimeWorkflowDefinitionEntity workflow = workflow(null);

        RuntimeWorkflowReleaseValidationResult result = service.validate(workflow);

        assertFalse(result.valid());
        assertTrue(hasError(result, "GRAPH_SPEC_MISSING"));
    }

    @Test
    void missingEntryNodeFails() {
        RuntimeWorkflowReleaseValidationService service = service(mock(RuntimeControlCatalogClient.class));
        RuntimeWorkflowDefinitionEntity workflow = workflow("""
                {"nodes":[{"id":"answer","type":"ANSWER"}],"edges":[]}
                """);

        RuntimeWorkflowReleaseValidationResult result = service.validate(workflow);

        assertFalse(result.valid());
        assertTrue(hasError(result, "GRAPH_ENTRY_MISSING"));
    }

    @Test
    void llmNodeRequiresModelInstanceWhenWorkflowDefaultIsMissing() {
        RuntimeWorkflowReleaseValidationService service = service(mock(RuntimeControlCatalogClient.class));
        RuntimeWorkflowDefinitionEntity workflow = workflow("""
                {
                  "nodes":[{"id":"answer","type":"LLM","config":{"prompt":"hello"}}],
                  "edges":[],
                  "entry":"answer",
                  "finish":["answer"]
                }
                """);

        RuntimeWorkflowReleaseValidationResult result = service.validate(workflow);

        assertFalse(result.valid());
        assertTrue(hasError(result, "GRAPH_MODEL_INSTANCE_REQUIRED"));
    }

    @Test
    void pageActionValidatesAgainstControlInternalCatalogApi() {
        RuntimeControlCatalogClient client = mock(RuntimeControlCatalogClient.class);
        RuntimeWorkflowReleaseValidationService service = service(client);
        when(client.getPageAction("demo", "orders", "open"))
                .thenReturn(activePageAction());
        RuntimeWorkflowDefinitionEntity workflow = workflow("""
                {
                  "nodes":[{"id":"open","type":"PAGE_ACTION","config":{"projectCode":"demo","pageKey":"orders","actionKey":"open"}}],
                  "edges":[],
                  "entry":"open",
                  "finish":["open"]
                }
                """);

        RuntimeWorkflowReleaseValidationResult result = service.validate(workflow);

        assertTrue(result.valid());
    }

    @Test
    void missingPageActionCatalogFails() {
        RuntimeControlCatalogClient client = mock(RuntimeControlCatalogClient.class);
        RuntimeWorkflowReleaseValidationService service = service(client);
        when(client.getPageAction("demo", "orders", "open"))
                .thenReturn(null);
        RuntimeWorkflowDefinitionEntity workflow = workflow("""
                {
                  "nodes":[{"id":"open","type":"PAGE_ACTION","config":{"projectCode":"demo","pageKey":"orders","actionKey":"open"}}],
                  "edges":[],
                  "entry":"open",
                  "finish":["open"]
                }
                """);

        RuntimeWorkflowReleaseValidationResult result = service.validate(workflow);

        assertFalse(result.valid());
        assertTrue(hasError(result, "GRAPH_PAGE_ACTION_CATALOG_MISSING"));
    }

    private RuntimeWorkflowReleaseValidationService service(RuntimeControlCatalogClient client) {
        return new RuntimeWorkflowReleaseValidationService(client, new ObjectMapper());
    }

    private boolean hasError(RuntimeWorkflowReleaseValidationResult result, String code) {
        return result.errors().stream().anyMatch(item -> code.equals(item.code()));
    }

    private RuntimeWorkflowDefinitionEntity workflow(String graphSpecJson) {
        RuntimeWorkflowDefinitionEntity entity = new RuntimeWorkflowDefinitionEntity();
        entity.setId("wf-1");
        entity.setProjectCode("demo");
        entity.setKeySlug("orders");
        entity.setName("Orders");
        entity.setRuntimeType("LANGGRAPH4J");
        entity.setGraphSpecJson(graphSpecJson);
        return entity;
    }

    private RuntimeControlCatalogClient.PageActionCatalogEntry activePageAction() {
        return new RuntimeControlCatalogClient.PageActionCatalogEntry("demo", "orders", "open", "ACTIVE");
    }
}
