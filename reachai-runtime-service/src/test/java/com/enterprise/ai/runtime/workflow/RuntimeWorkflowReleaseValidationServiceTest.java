package com.enterprise.ai.runtime.workflow;

import com.enterprise.ai.agent.graph.GraphSpec;
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
    void jsonNullGraphSpecFailsAsInvalid() {
        RuntimeWorkflowReleaseValidationService service = service(mock(RuntimeControlCatalogClient.class));
        RuntimeWorkflowDefinitionEntity workflow = workflow("null");

        RuntimeWorkflowReleaseValidationResult result = service.validate(workflow);

        assertFalse(result.valid());
        assertTrue(hasError(result, "GRAPH_SPEC_INVALID"));
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
    void proposedValidationUsesInMemoryWorkflowDefaultModelOverride() throws Exception {
        RuntimeWorkflowReleaseValidationService service = service(mock(RuntimeControlCatalogClient.class));
        RuntimeWorkflowDefinitionEntity workflow = workflow(null);
        workflow.setDefaultModelInstanceId("db-model");
        GraphSpec proposed = new ObjectMapper().readValue("""
                {
                  "nodes":[{"id":"answer","type":"LLM","config":{"prompt":"hello"}}],
                  "edges":[],
                  "entry":"answer",
                  "finish":["answer"]
                }
                """, GraphSpec.class);

        RuntimeWorkflowReleaseValidationResult persistedDefault = service.validateProposed(workflow, proposed);
        workflow.setDefaultModelInstanceId("");
        RuntimeWorkflowReleaseValidationResult explicitBlankOverride = service.validateProposed(workflow, proposed);

        assertTrue(persistedDefault.valid());
        assertFalse(explicitBlankOverride.valid());
        assertTrue(hasError(explicitBlankOverride, "GRAPH_MODEL_INSTANCE_REQUIRED"));
    }

    @Test
    void keywordIntentClassifierDoesNotRequireModelInstance() {
        RuntimeWorkflowReleaseValidationService service = service(mock(RuntimeControlCatalogClient.class));
        RuntimeWorkflowDefinitionEntity workflow = workflow("""
                {
                  "nodes":[{"id":"classifier","type":"INTENT_CLASSIFIER","config":{
                    "strategy":"KEYWORD",
                    "classes":[{"id":"search","keywords":["查询"]}],
                    "defaultRoute":"else"
                  }},
                  {"id":"search-answer","type":"ANSWER"},
                  {"id":"fallback-answer","type":"ANSWER"}],
                  "edges":[
                    {"from":"classifier","to":"search-answer","condition":"search"},
                    {"from":"classifier","to":"fallback-answer","condition":"else"}],
                  "entry":"classifier",
                  "finish":["search-answer","fallback-answer"]
                }
                """);

        RuntimeWorkflowReleaseValidationResult result = service.validate(workflow);

        assertTrue(result.valid());
        assertFalse(hasError(result, "GRAPH_MODEL_INSTANCE_REQUIRED"));
    }

    @Test
    void llmAndHybridIntentClassifiersRequireModelInstance() {
        RuntimeWorkflowReleaseValidationService service = service(mock(RuntimeControlCatalogClient.class));
        RuntimeWorkflowDefinitionEntity llmWorkflow = classifierWorkflow("LLM");
        RuntimeWorkflowDefinitionEntity hybridWorkflow = classifierWorkflow("HYBRID");

        RuntimeWorkflowReleaseValidationResult llmResult = service.validate(llmWorkflow);
        RuntimeWorkflowReleaseValidationResult hybridResult = service.validate(hybridWorkflow);

        assertFalse(llmResult.valid());
        assertTrue(hasError(llmResult, "GRAPH_MODEL_INSTANCE_REQUIRED"));
        assertFalse(hybridResult.valid());
        assertTrue(hasError(hybridResult, "GRAPH_MODEL_INSTANCE_REQUIRED"));
    }

    @Test
    void knownNodeTypeWithoutRuntimeExecutorFailsPublishing() {
        RuntimeWorkflowReleaseValidationService service = service(mock(RuntimeControlCatalogClient.class));
        RuntimeWorkflowDefinitionEntity workflow = workflow("""
                {
                  "nodes":[{"id":"code","type":"CODE","config":{"code":"return 1"}}],
                  "edges":[],
                  "entry":"code",
                  "finish":["code"]
                }
                """);

        RuntimeWorkflowReleaseValidationResult result = service.validate(workflow);

        assertFalse(result.valid());
        assertTrue(hasError(result, "GRAPH_NODE_RUNTIME_UNSUPPORTED"));
        assertFalse(hasError(result, "GRAPH_NODE_TYPE_UNSUPPORTED"));
    }

    @Test
    void toolAndCapabilityNodesRequireExecutableReference() {
        RuntimeWorkflowReleaseValidationService service = service(mock(RuntimeControlCatalogClient.class));
        RuntimeWorkflowDefinitionEntity workflow = workflow("""
                {
                  "nodes":[
                    {"id":"tool","type":"TOOL","config":{"inputMapping":{}}},
                    {"id":"capability","type":"CAPABILITY","config":{}}
                  ],
                  "edges":[{"from":"tool","to":"capability"}],
                  "entry":"tool",
                  "finish":["capability"]
                }
                """);

        RuntimeWorkflowReleaseValidationResult result = service.validate(workflow);

        assertFalse(result.valid());
        assertTrue(result.errors().stream()
                .filter(item -> "GRAPH_TOOL_REF_REQUIRED".equals(item.code()))
                .map(RuntimeWorkflowReleaseValidationResult.Item::nodeId)
                .toList()
                .containsAll(java.util.List.of("tool", "capability")));
    }

    @Test
    void toolReferenceAcceptsNodeRefAndNestedConfiguration() {
        RuntimeWorkflowReleaseValidationService service = service(mock(RuntimeControlCatalogClient.class));
        RuntimeWorkflowDefinitionEntity workflow = workflow("""
                {
                  "nodes":[
                    {"id":"tool","type":"TOOL","ref":{"qualifiedName":"orders:query"}},
                    {"id":"capability","type":"CAPABILITY","config":{
                      "capabilityConfig":{"ref":{"qualifiedName":"orders:submit"}}
                    }}
                  ],
                  "edges":[{"from":"tool","to":"capability"}],
                  "entry":"tool",
                  "finish":["capability"]
                }
                """);

        RuntimeWorkflowReleaseValidationResult result = service.validate(workflow);

        assertTrue(result.valid());
        assertFalse(hasError(result, "GRAPH_TOOL_REF_REQUIRED"));
    }

    @Test
    void nestedClassifierConfigProvidesStrategyModelClassesAndRoutes() {
        RuntimeWorkflowReleaseValidationService service = service(mock(RuntimeControlCatalogClient.class));
        RuntimeWorkflowDefinitionEntity workflow = workflow("""
                {
                  "nodes":[
                    {"id":"classifier","type":"INTENT_CLASSIFIER","config":{"classifierConfig":{
                      "strategy":"LLM",
                      "modelInstanceId":"classifier-model",
                      "classes":[
                        {"id":"search","keywords":["查询"]},
                        {"id":"reset","keywords":["重置"]}
                      ],
                      "defaultRoute":"else"
                    }}},
                    {"id":"search-answer","type":"ANSWER"},
                    {"id":"reset-answer","type":"ANSWER"},
                    {"id":"fallback-answer","type":"ANSWER"}
                  ],
                  "edges":[
                    {"from":"classifier","to":"search-answer","condition":"search"},
                    {"from":"classifier","to":"reset-answer","condition":"route:reset"},
                    {"from":"classifier","to":"fallback-answer","condition":"else"}
                  ],
                  "entry":"classifier",
                  "finish":["search-answer","reset-answer","fallback-answer"]
                }
                """);

        RuntimeWorkflowReleaseValidationResult result = service.validate(workflow);

        assertTrue(result.valid());
        assertFalse(hasError(result, "GRAPH_MODEL_INSTANCE_REQUIRED"));
        assertFalse(hasWarning(result, "GRAPH_CLASSIFIER_CLASS_ROUTE_MISSING"));
        assertFalse(hasWarning(result, "GRAPH_CLASSIFIER_DEFAULT_ROUTE_MISSING"));
    }

    @Test
    void classifierRequiresNonEmptyClassesWithUniqueIds() {
        RuntimeWorkflowReleaseValidationService service = service(mock(RuntimeControlCatalogClient.class));
        RuntimeWorkflowDefinitionEntity missingClasses = workflow("""
                {
                  "nodes":[{"id":"classifier","type":"INTENT_CLASSIFIER","config":{"strategy":"KEYWORD"}}],
                  "edges":[],
                  "entry":"classifier",
                  "finish":["classifier"]
                }
                """);
        RuntimeWorkflowDefinitionEntity emptyClasses = workflow("""
                {
                  "nodes":[{"id":"classifier","type":"INTENT_CLASSIFIER","config":{
                    "strategy":"KEYWORD",
                    "classes":[]
                  }}],
                  "edges":[],
                  "entry":"classifier",
                  "finish":["classifier"]
                }
                """);
        RuntimeWorkflowDefinitionEntity invalidClassIds = workflow("""
                {
                  "nodes":[{"id":"classifier","type":"INTENT_CLASSIFIER","config":{
                    "strategy":"KEYWORD",
                    "classes":[{"id":""},{"id":"search"},{"id":"Search"}]
                  }}],
                  "edges":[],
                  "entry":"classifier",
                  "finish":["classifier"]
                }
                """);

        RuntimeWorkflowReleaseValidationResult missingResult = service.validate(missingClasses);
        RuntimeWorkflowReleaseValidationResult emptyResult = service.validate(emptyClasses);
        RuntimeWorkflowReleaseValidationResult invalidResult = service.validate(invalidClassIds);

        assertFalse(missingResult.valid());
        assertTrue(hasError(missingResult, "GRAPH_CLASSIFIER_CLASSES_REQUIRED"));
        assertFalse(emptyResult.valid());
        assertTrue(hasError(emptyResult, "GRAPH_CLASSIFIER_CLASSES_REQUIRED"));
        assertFalse(invalidResult.valid());
        assertTrue(hasError(invalidResult, "GRAPH_CLASSIFIER_CLASS_ID_EMPTY"));
        assertTrue(hasError(invalidResult, "GRAPH_CLASSIFIER_CLASS_ID_DUPLICATE"));
    }

    @Test
    void missingClassifierRouteEdgesBlockRelease() {
        RuntimeWorkflowReleaseValidationService service = service(mock(RuntimeControlCatalogClient.class));
        RuntimeWorkflowDefinitionEntity workflow = workflow("""
                {
                  "nodes":[
                    {"id":"classifier","type":"INTENT_CLASSIFIER","config":{
                      "strategy":"KEYWORD",
                      "classes":[{"id":"search","keywords":["查询"]}],
                      "defaultRoute":"else"
                    }},
                    {"id":"answer","type":"ANSWER"}
                  ],
                  "edges":[{"from":"classifier","to":"answer","condition":"always"}],
                  "entry":"classifier",
                  "finish":["answer"]
                }
                """);

        RuntimeWorkflowReleaseValidationResult result = service.validate(workflow);

        assertFalse(result.valid());
        assertTrue(hasError(result, "GRAPH_CLASSIFIER_CLASS_ROUTE_MISSING"));
        assertTrue(hasError(result, "GRAPH_CLASSIFIER_DEFAULT_ROUTE_MISSING"));
    }

    @Test
    void conditionRequiresEdgesForEveryGroupAndDefaultRoute() {
        RuntimeWorkflowReleaseValidationService service = service(mock(RuntimeControlCatalogClient.class));
        RuntimeWorkflowDefinitionEntity workflow = workflow("""
                {
                  "nodes":[
                    {"id":"judge","type":"IF_ELSE","config":{
                      "conditionGroups":[
                        {"id":"paid","conditions":[{"left":"status","operator":"equals","right":"PAID"}]},
                        {"id":"refunded","conditions":[{"left":"status","operator":"equals","right":"REFUNDED"}]}
                      ],
                      "defaultRoute":"else"
                    }},
                    {"id":"paid-answer","type":"ANSWER"}
                  ],
                  "edges":[{"from":"judge","to":"paid-answer","condition":"route:paid"}],
                  "entry":"judge",
                  "finish":["paid-answer"]
                }
                """);

        RuntimeWorkflowReleaseValidationResult result = service.validate(workflow);

        assertFalse(result.valid());
        assertTrue(hasError(result, "GRAPH_CONDITION_GROUP_ROUTE_MISSING"));
        assertTrue(hasError(result, "GRAPH_CONDITION_DEFAULT_ROUTE_MISSING"));
        assertTrue(result.errors().stream().anyMatch(item -> item.message().endsWith("refunded")));
    }

    @Test
    void nestedConditionConfigAcceptsDirectRouteAndDefaultAliasToEnd() {
        RuntimeWorkflowReleaseValidationService service = service(mock(RuntimeControlCatalogClient.class));
        RuntimeWorkflowDefinitionEntity workflow = workflow("""
                {
                  "nodes":[{"id":"judge","type":"IF_ELSE","config":{"conditionConfig":{
                    "groups":[{"id":"paid","conditions":[
                      {"left":"status","operator":"equals","right":"PAID"}
                    ]}],
                    "defaultRoute":"else"
                  }}}],
                  "edges":[
                    {"from":"judge","to":"END","condition":"paid"},
                    {"from":"judge","to":"END","condition":"route:default"}
                  ],
                  "entry":"judge",
                  "finish":["judge"]
                }
                """);

        RuntimeWorkflowReleaseValidationResult result = service.validate(workflow);

        assertTrue(result.valid());
        assertFalse(hasError(result, "GRAPH_CONDITION_GROUP_ROUTE_MISSING"));
        assertFalse(hasError(result, "GRAPH_CONDITION_DEFAULT_ROUTE_MISSING"));
    }

    @Test
    void expressionParameterExtractRequiresFieldsButNotModel() {
        RuntimeWorkflowReleaseValidationService service = service(mock(RuntimeControlCatalogClient.class));
        RuntimeWorkflowDefinitionEntity valid = workflow("""
                {
                  "nodes":[{"id":"extract","type":"PARAMETER_EXTRACT","config":{
                    "extractMode":"expression",
                    "fields":[{"name":"owner","type":"string","source":"params.owner"}]
                  }}],
                  "edges":[],
                  "entry":"extract",
                  "finish":["extract"]
                }
                """);
        RuntimeWorkflowDefinitionEntity missingFields = workflow("""
                {
                  "nodes":[{"id":"extract","type":"PARAMETER_EXTRACT","config":{
                    "extractMode":"expression",
                    "fields":[]
                  }}],
                  "edges":[],
                  "entry":"extract",
                  "finish":["extract"]
                }
                """);

        RuntimeWorkflowReleaseValidationResult validResult = service.validate(valid);
        RuntimeWorkflowReleaseValidationResult missingResult = service.validate(missingFields);

        assertTrue(validResult.valid());
        assertFalse(hasError(validResult, "GRAPH_MODEL_INSTANCE_REQUIRED"));
        assertFalse(missingResult.valid());
        assertTrue(hasError(missingResult, "GRAPH_PARAMETER_FIELDS_REQUIRED"));
    }

    @Test
    void llmParameterExtractRequiresModelInstance() {
        RuntimeWorkflowReleaseValidationService service = service(mock(RuntimeControlCatalogClient.class));
        RuntimeWorkflowDefinitionEntity workflow = workflow("""
                {
                  "nodes":[{"id":"extract","type":"PARAMETER_EXTRACT","config":{"parameterConfig":{
                    "mode":"llm",
                    "fields":[{"name":"owner","type":"string"}]
                  }}}],
                  "edges":[],
                  "entry":"extract",
                  "finish":["extract"]
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

    private boolean hasWarning(RuntimeWorkflowReleaseValidationResult result, String code) {
        return result.warnings().stream().anyMatch(item -> code.equals(item.code()));
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

    private RuntimeWorkflowDefinitionEntity classifierWorkflow(String strategy) {
        return workflow("""
                {
                  "nodes":[{"id":"classifier","type":"INTENT_CLASSIFIER","config":{
                    "strategy":"%s",
                    "classes":[{"id":"search","keywords":["查询"]}],
                    "defaultRoute":"else"
                  }}],
                  "edges":[],
                  "entry":"classifier",
                  "finish":["classifier"]
                }
                """.formatted(strategy));
    }

    private RuntimeControlCatalogClient.PageActionCatalogEntry activePageAction() {
        return new RuntimeControlCatalogClient.PageActionCatalogEntry("demo", "orders", "open", "ACTIVE");
    }
}
