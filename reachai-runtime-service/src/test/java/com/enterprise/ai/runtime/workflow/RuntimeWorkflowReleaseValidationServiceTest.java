package com.enterprise.ai.runtime.workflow;

import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.runtime.client.control.RuntimeControlCatalogClient;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowResourceBindingService.BindingView;
import com.enterprise.ai.runtime.workflow.node.RuntimeWorkflowNodeCapabilityDescriptor;
import com.enterprise.ai.runtime.workflow.node.RuntimeWorkflowNodeCapabilityRegistry;
import com.enterprise.ai.runtime.workflow.node.WorkflowNodeMaturity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

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
    void pageAssistantRequiresCanonicalUserInputContract() {
        RuntimeWorkflowReleaseValidationService service = service(mock(RuntimeControlCatalogClient.class));
        RuntimeWorkflowDefinitionEntity workflow = workflow("""
                {
                  "schemaVersion":2,
                  "nodes":[{"id":"answer","type":"ANSWER"}],
                  "edges":[],
                  "entryNodeId":"answer",
                  "exitNodeIds":["answer"]
                }
                """);
        workflow.setWorkflowKind(WorkflowSemanticValues.KIND_PAGE_ASSISTANT);

        RuntimeWorkflowReleaseValidationResult result = service.validate(workflow);

        assertFalse(result.valid());
        assertTrue(hasError(result, "PAGE_ASSISTANT_USER_INPUT_REQUIRED"));
    }

    @Test
    void pageAssistantStarterGraphUsesTheSamePublishContract() {
        RuntimeWorkflowResourceBindingService bindings = mock(RuntimeWorkflowResourceBindingService.class);
        when(bindings.list("wf-1")).thenReturn(List.of(new BindingView(
                1L, "wf-1", 1L, "demo", "PAGE", "orders.detail", "TARGET", "ACTIVE", null)));
        RuntimeWorkflowReleaseValidationService service = new RuntimeWorkflowReleaseValidationService(
                mock(RuntimeControlCatalogClient.class),
                new ObjectMapper(),
                new RuntimeWorkflowNodeCapabilityRegistry(),
                bindings);
        RuntimeWorkflowDefinitionEntity workflow = workflow(null);
        workflow.setWorkflowKind(WorkflowSemanticValues.KIND_PAGE_ASSISTANT);
        try {
            workflow.setGraphSpecJson(new ObjectMapper().writeValueAsString(
                    RuntimeWorkflowInputContract.pageAssistantStarterGraph()));
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }

        RuntimeWorkflowReleaseValidationResult result = service.validate(workflow);

        assertTrue(result.valid());
    }

    @Test
    void llmNodeRequiresModelInstanceWhenWorkflowDefaultIsMissing() {
        RuntimeWorkflowReleaseValidationService service = service(mock(RuntimeControlCatalogClient.class));
        RuntimeWorkflowDefinitionEntity workflow = workflow("""
                {
                  "nodes":[{"id":"answer","type":"LLM","config":{"prompt":"hello"}}],
                  "edges":[],
                  "entryNodeId":"answer",
                  "exitNodeIds":["answer"]
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
                  "entryNodeId":"answer",
                  "exitNodeIds":["answer"]
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
                  "entryNodeId":"classifier",
                  "exitNodeIds":["search-answer","fallback-answer"]
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
                  "entryNodeId":"code",
                  "exitNodeIds":["code"]
                }
                """);

        RuntimeWorkflowReleaseValidationResult result = service.validate(workflow);

        assertFalse(result.valid());
        assertTrue(hasError(result, "GRAPH_NODE_RUNTIME_UNSUPPORTED"));
        assertFalse(hasError(result, "GRAPH_NODE_TYPE_UNSUPPORTED"));
        assertFalse(hasError(result, "GRAPH_NODE_NOT_PUBLISHABLE"));
    }

    @Test
    void interactionNodeFailsPublishingAsNotPublishableWhileRegistryClosed() {
        RuntimeWorkflowReleaseValidationService service = service(mock(RuntimeControlCatalogClient.class));
        RuntimeWorkflowDefinitionEntity workflow = workflow("""
                {
                  "nodes":[{"id":"ask","type":"INTERACTION","config":{"mode":"confirm_action"}}],
                  "edges":[],
                  "entryNodeId":"ask",
                  "exitNodeIds":["ask"]
                }
                """);

        RuntimeWorkflowReleaseValidationResult result = service.validate(workflow);

        assertFalse(result.valid());
        assertTrue(hasError(result, "GRAPH_NODE_NOT_PUBLISHABLE"));
        assertFalse(hasError(result, "GRAPH_NODE_RUNTIME_UNSUPPORTED"));
        assertFalse(hasError(result, "GRAPH_NODE_TYPE_UNSUPPORTED"));
    }

    @Test
    void interactionCollectInputValidatesFieldsWhenPublishable() {
        RuntimeWorkflowReleaseValidationService service = openInteractionService();
        RuntimeWorkflowDefinitionEntity missingFields = workflow("""
                {
                  "nodes":[{"id":"ask","type":"INTERACTION","config":{"interactionType":"COLLECT_INPUT"}}],
                  "edges":[],
                  "entryNodeId":"ask",
                  "exitNodeIds":["ask"]
                }
                """);
        RuntimeWorkflowDefinitionEntity valid = workflow("""
                {
                  "nodes":[{"id":"ask","type":"INTERACTION","config":{
                    "interactionType":"COLLECT_INPUT",
                    "fields":[{"key":"q","type":"string","required":true}]
                  }}],
                  "edges":[],
                  "entryNodeId":"ask",
                  "exitNodeIds":["ask"]
                }
                """);

        assertTrue(hasError(service.validate(missingFields), "GRAPH_INTERACTION_FIELDS_REQUIRED"));
        assertTrue(service.validate(valid).valid());
    }

    @Test
    void interactionConfirmRequiresConfirmRouteAndRejectsUnsafeAlwaysFallback() {
        RuntimeWorkflowReleaseValidationService service = openInteractionService();
        RuntimeWorkflowDefinitionEntity unsafe = workflow("""
                {
                  "nodes":[
                    {"id":"ask","type":"INTERACTION","config":{"interactionType":"CONFIRM_ACTION"}},
                    {"id":"write","type":"ANSWER","config":{"template":"done"}}
                  ],
                  "edges":[{"from":"ask","to":"write","condition":"always"}],
                  "entryNodeId":"ask",
                  "exitNodeIds":["write"]
                }
                """);
        RuntimeWorkflowDefinitionEntity valid = workflow("""
                {
                  "nodes":[
                    {"id":"ask","type":"INTERACTION","config":{"interactionType":"CONFIRM_ACTION"}},
                    {"id":"write","type":"ANSWER","config":{"template":"done"}},
                    {"id":"reject","type":"ANSWER","config":{"template":"cancelled"}}
                  ],
                  "edges":[
                    {"from":"ask","to":"write","condition":"route:confirm"},
                    {"from":"ask","to":"reject","condition":"route:reject"}
                  ],
                  "entryNodeId":"ask",
                  "exitNodeIds":["write","reject"]
                }
                """);

        RuntimeWorkflowReleaseValidationResult unsafeResult = service.validate(unsafe);
        assertTrue(hasError(unsafeResult, "GRAPH_INTERACTION_CONFIRM_ROUTE_MISSING")
                || hasError(unsafeResult, "GRAPH_INTERACTION_REJECT_FALLBACK_UNSAFE"));
        assertTrue(service.validate(valid).valid());
    }

    @Test
    void interactionCustomAndReviewEditFailClosed() {
        RuntimeWorkflowReleaseValidationService service = openInteractionService();
        RuntimeWorkflowDefinitionEntity badCustom = workflow("""
                {
                  "nodes":[{"id":"ask","type":"INTERACTION","config":{
                    "interactionType":"CUSTOM","rendererKey":"evil_html"
                  }}],
                  "edges":[],
                  "entryNodeId":"ask",
                  "exitNodeIds":["ask"]
                }
                """);
        RuntimeWorkflowDefinitionEntity reviewEdit = workflow("""
                {
                  "nodes":[{"id":"ask","type":"INTERACTION","config":{
                    "interactionType":"REVIEW_EDIT",
                    "fields":[{"key":"text","type":"string"}]
                  }}],
                  "edges":[],
                  "entryNodeId":"ask",
                  "exitNodeIds":["ask"]
                }
                """);

        assertTrue(hasError(service.validate(badCustom), "GRAPH_INTERACTION_CUSTOM_RENDERER_UNSUPPORTED"));
        assertTrue(hasError(service.validate(reviewEdit), "GRAPH_INTERACTION_TYPE_UNSUPPORTED"));
    }

    @Test
    void interactionPresentOutputPublishesWhileBlockingVariantsStayClosed() {
        RuntimeWorkflowReleaseValidationService service = service(mock(RuntimeControlCatalogClient.class));
        RuntimeWorkflowDefinitionEntity valid = workflow("""
                {
                  "nodes":[{"id":"show","type":"INTERACTION","config":{
                    "interactionType":"PRESENT_OUTPUT",
                    "component":"LIST_CARD",
                    "dataExpression":"var.results",
                    "renderSchema":{
                      "titleField":"name",
                      "initialVisibleCount":5,
                      "fields":[{"key":"owner","label":"Owner"}]
                    }
                  }}],
                  "edges":[],
                  "entryNodeId":"show",
                  "exitNodeIds":["show"]
                }
                """);
        RuntimeWorkflowDefinitionEntity invalid = workflow("""
                {
                  "nodes":[{"id":"show","type":"INTERACTION","config":{
                    "interactionType":"PRESENT_OUTPUT",
                    "component":"list-card",
                    "presentation":{"mode":"cards"},
                    "renderSchema":{"initialVisibleCount":0,"fields":{}}
                  }}],
                  "edges":[],
                  "entryNodeId":"show",
                  "exitNodeIds":["show"]
                }
                """);

        assertTrue(service.validate(valid).valid(), () -> service.validate(valid).errors().toString());
        RuntimeWorkflowReleaseValidationResult invalidResult = service.validate(invalid);
        assertTrue(hasError(invalidResult, "GRAPH_INTERACTION_LIST_CARD_VISIBLE_COUNT_INVALID"));
        assertTrue(hasError(invalidResult, "GRAPH_INTERACTION_LIST_CARD_FIELDS_INVALID"));
        assertTrue(hasError(invalidResult, "GRAPH_INTERACTION_PRESENTATION_MODE_UNSUPPORTED"));
    }

    @Test
    void blockingInteractionCannotHideItsInteractiveCard() {
        RuntimeWorkflowReleaseValidationService service = openInteractionService();
        RuntimeWorkflowDefinitionEntity workflow = workflow("""
                {
                  "nodes":[{"id":"ask","type":"INTERACTION","config":{
                    "interactionType":"COLLECT_INPUT",
                    "presentation":{"mode":"text_only"},
                    "fields":[{"key":"q","type":"string","required":true}]
                  }}],
                  "edges":[],
                  "entryNodeId":"ask",
                  "exitNodeIds":["ask"]
                }
                """);

        RuntimeWorkflowReleaseValidationResult result = service.validate(workflow);

        assertTrue(hasError(result, "GRAPH_INTERACTION_PRESENTATION_TEXT_ONLY_UNSAFE"));
    }

    @Test
    void toolAndCapabilityNodesRequireExecutableReference() {
        RuntimeWorkflowReleaseValidationService service = service(mock(RuntimeControlCatalogClient.class));
        RuntimeWorkflowDefinitionEntity workflow = workflow("""
                {
                  "nodes":[
                    {"id":"tool","type":"TOOL","config":{"inputMapping":{}}},
                    {"id":"capability","type":"TOOL","config":{}}
                  ],
                  "edges":[{"from":"tool","to":"capability"}],
                  "entryNodeId":"tool",
                  "exitNodeIds":["capability"]
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
                    {"id":"capability","type":"TOOL","config":{
                      "toolConfig":{"ref":{"qualifiedName":"orders:submit"}}
                    }}
                  ],
                  "edges":[{"from":"tool","to":"capability"}],
                  "entryNodeId":"tool",
                  "exitNodeIds":["capability"]
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
                  "entryNodeId":"classifier",
                  "exitNodeIds":["search-answer","reset-answer","fallback-answer"]
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
                  "entryNodeId":"classifier",
                  "exitNodeIds":["classifier"]
                }
                """);
        RuntimeWorkflowDefinitionEntity emptyClasses = workflow("""
                {
                  "nodes":[{"id":"classifier","type":"INTENT_CLASSIFIER","config":{
                    "strategy":"KEYWORD",
                    "classes":[]
                  }}],
                  "edges":[],
                  "entryNodeId":"classifier",
                  "exitNodeIds":["classifier"]
                }
                """);
        RuntimeWorkflowDefinitionEntity invalidClassIds = workflow("""
                {
                  "nodes":[{"id":"classifier","type":"INTENT_CLASSIFIER","config":{
                    "strategy":"KEYWORD",
                    "classes":[{"id":""},{"id":"search"},{"id":"Search"}]
                  }}],
                  "edges":[],
                  "entryNodeId":"classifier",
                  "exitNodeIds":["classifier"]
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
                  "entryNodeId":"classifier",
                  "exitNodeIds":["answer"]
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
                  "entryNodeId":"judge",
                  "exitNodeIds":["paid-answer"]
                }
                """);

        RuntimeWorkflowReleaseValidationResult result = service.validate(workflow);

        assertFalse(result.valid());
        assertTrue(hasError(result, "GRAPH_CONDITION_GROUP_ROUTE_MISSING"));
        assertTrue(hasError(result, "GRAPH_CONDITION_DEFAULT_ROUTE_MISSING"));
        assertTrue(result.errors().stream().anyMatch(item -> item.message().endsWith("refunded")));
    }

    @Test
    void nestedConditionConfigAcceptsDirectRouteAndDefaultRouteToRealExitNodes() {
        RuntimeWorkflowReleaseValidationService service = service(mock(RuntimeControlCatalogClient.class));
        RuntimeWorkflowDefinitionEntity workflow = workflow("""
                {
                  "nodes":[
                    {"id":"judge","type":"IF_ELSE","config":{"conditionConfig":{
                    "groups":[{"id":"paid","conditions":[
                      {"left":"status","operator":"equals","right":"PAID"}
                    ]}],
                    "defaultRoute":"else"
                    }}},
                    {"id":"paid-answer","type":"ANSWER"},
                    {"id":"default-answer","type":"ANSWER"}
                  ],
                  "edges":[
                    {"from":"judge","to":"paid-answer","condition":"paid"},
                    {"from":"judge","to":"default-answer","condition":"route:default"}
                  ],
                  "entryNodeId":"judge",
                  "exitNodeIds":["paid-answer","default-answer"]
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
                  "entryNodeId":"extract",
                  "exitNodeIds":["extract"]
                }
                """);
        RuntimeWorkflowDefinitionEntity missingFields = workflow("""
                {
                  "nodes":[{"id":"extract","type":"PARAMETER_EXTRACT","config":{
                    "extractMode":"expression",
                    "fields":[]
                  }}],
                  "edges":[],
                  "entryNodeId":"extract",
                  "exitNodeIds":["extract"]
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
                  "entryNodeId":"extract",
                  "exitNodeIds":["extract"]
                }
                """);

        RuntimeWorkflowReleaseValidationResult result = service.validate(workflow);

        assertFalse(result.valid());
        assertTrue(hasError(result, "GRAPH_MODEL_INSTANCE_REQUIRED"));
    }

    @Test
    void llmParameterExtractCannotHideNodeOutputBehindCustomUserPrompt() {
        RuntimeWorkflowReleaseValidationService service = service(mock(RuntimeControlCatalogClient.class));
        RuntimeWorkflowDefinitionEntity invalid = workflow("""
                {
                  "nodes":[{"id":"extract","type":"PARAMETER_EXTRACT","config":{
                    "extractMode":"llm",
                    "modelInstanceId":"model-1",
                    "inputExpression":"nodeOutput.lookup",
                    "userPrompt":"目标名称：{{ params.name }}",
                    "fields":[{"name":"id","type":"string","required":true}]
                  }}],
                  "edges":[],
                  "entryNodeId":"extract",
                  "exitNodeIds":["extract"]
                }
                """);
        RuntimeWorkflowDefinitionEntity valid = workflow("""
                {
                  "nodes":[{"id":"extract","type":"PARAMETER_EXTRACT","config":{
                    "extractMode":"llm",
                    "modelInstanceId":"model-1",
                    "inputExpression":"nodeOutput.lookup",
                    "userPrompt":"目标名称：{{ params.name }}；列表：{{ nodeOutput.lookup }}",
                    "fields":[{"name":"id","type":"string","required":true}]
                  }}],
                  "edges":[],
                  "entryNodeId":"extract",
                  "exitNodeIds":["extract"]
                }
                """);

        RuntimeWorkflowReleaseValidationResult invalidResult = service.validate(invalid);
        RuntimeWorkflowReleaseValidationResult validResult = service.validate(valid);

        assertTrue(hasError(invalidResult, "GRAPH_PARAMETER_USER_PROMPT_INPUT_MISSING"));
        assertFalse(hasError(validResult, "GRAPH_PARAMETER_USER_PROMPT_INPUT_MISSING"));
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
                  "entryNodeId":"open",
                  "exitNodeIds":["open"]
                }
                """);

        RuntimeWorkflowReleaseValidationResult result = service.validate(workflow);

        assertTrue(result.valid());
        assertTrue(hasWarning(result, "GRAPH_NODE_BETA"));
        assertFalse(hasError(result, "GRAPH_NODE_NOT_PUBLISHABLE"));
    }

    @Test
    void pageActionNodeOutputRejectsASecondUndeclaredDataWrapper() {
        RuntimeControlCatalogClient client = mock(RuntimeControlCatalogClient.class);
        RuntimeWorkflowReleaseValidationService service = service(client);
        when(client.getPageAction("demo", "orders", "readDetail"))
                .thenReturn(pageAction(
                        "readDetail",
                        Map.of(),
                        Map.of(
                                "type", "object",
                                "properties", Map.of("id", Map.of("type", "string")))));
        when(client.getPageAction("demo", "orders", "close"))
                .thenReturn(pageAction(
                        "close",
                        Map.of("type", "object", "required", List.of("id")),
                        Map.of("type", "object")));
        RuntimeWorkflowDefinitionEntity workflow = workflow("""
                {
                  "nodes":[
                    {"id":"read","type":"PAGE_ACTION","config":{"projectCode":"demo","pageKey":"orders","actionKey":"readDetail"}},
                    {"id":"close","type":"PAGE_ACTION","config":{"projectCode":"demo","pageKey":"orders","actionKey":"close","args":{"id":"nodeOutput.read.data.id"}}}
                  ],
                  "edges":[{"from":"read","to":"close","condition":"always"}],
                  "entryNodeId":"read",
                  "exitNodeIds":["close"]
                }
                """);

        RuntimeWorkflowReleaseValidationResult result = service.validate(workflow);

        assertFalse(result.valid());
        assertTrue(hasError(result, "GRAPH_PAGE_ACTION_OUTPUT_DATA_REDUNDANT"));
    }

    @Test
    void pageActionNodeOutputAllowsDataWhenBusinessSchemaDeclaresThatProperty() {
        RuntimeControlCatalogClient client = mock(RuntimeControlCatalogClient.class);
        RuntimeWorkflowReleaseValidationService service = service(client);
        when(client.getPageAction("demo", "orders", "readEnvelope"))
                .thenReturn(pageAction(
                        "readEnvelope",
                        Map.of(),
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "data", Map.of(
                                                "type", "object",
                                                "properties", Map.of("id", Map.of("type", "string")))))));
        when(client.getPageAction("demo", "orders", "close"))
                .thenReturn(pageAction(
                        "close",
                        Map.of("type", "object", "required", List.of("id")),
                        Map.of("type", "object")));
        RuntimeWorkflowDefinitionEntity workflow = workflow("""
                {
                  "nodes":[
                    {"id":"read","type":"PAGE_ACTION","config":{"projectCode":"demo","pageKey":"orders","actionKey":"readEnvelope"}},
                    {"id":"close","type":"PAGE_ACTION","config":{"projectCode":"demo","pageKey":"orders","actionKey":"close","args":{"id":"nodeOutput.read.data.id"}}}
                  ],
                  "edges":[{"from":"read","to":"close","condition":"always"}],
                  "entryNodeId":"read",
                  "exitNodeIds":["close"]
                }
                """);

        RuntimeWorkflowReleaseValidationResult result = service.validate(workflow);

        assertFalse(hasError(result, "GRAPH_PAGE_ACTION_OUTPUT_DATA_REDUNDANT"));
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
                  "entryNodeId":"open",
                  "exitNodeIds":["open"]
                }
                """);

        RuntimeWorkflowReleaseValidationResult result = service.validate(workflow);

        assertFalse(result.valid());
        assertTrue(hasError(result, "GRAPH_PAGE_ACTION_CATALOG_MISSING"));
    }

    @Test
    void phase1NodesValidConfigCanPublishIncludingKnowledgeHttp() {
        RuntimeWorkflowReleaseValidationService service = service(mock(RuntimeControlCatalogClient.class));
        RuntimeWorkflowDefinitionEntity workflow = workflow("""
                {
                  "entryNodeId":"assign",
                  "nodes":[
                    {"id":"assign","type":"VARIABLE_ASSIGN","config":{"assignments":{"var.count":1,"var.flag":true}}},
                    {"id":"tpl","type":"TEMPLATE","config":{"template":"hi {{ var.count }}","outputAlias":"tpl_out"}},
                    {"id":"agg","type":"VARIABLE_AGGREGATOR","config":{"mode":"object","items":[{"name":"a","source":"var.count"}]}},
                    {"id":"kb","type":"KNOWLEDGE_RETRIEVAL","config":{"knowledgeBaseCodes":["kb1"],"query":"input","searchMode":"hybrid"}},
                    {"id":"http","type":"HTTP_REQUEST","config":{"method":"GET","url":"https://example.com/api"}},
                    {"id":"answer","type":"ANSWER","config":{"template":"done"}}
                  ],
                  "edges":[
                    {"from":"assign","to":"tpl","condition":"always"},
                    {"from":"tpl","to":"agg","condition":"always"},
                    {"from":"agg","to":"kb","condition":"always"},
                    {"from":"kb","to":"http","condition":"always"},
                    {"from":"http","to":"answer","condition":"always"}
                  ],
                  "exitNodeIds":["answer"]
                }
                """);
        RuntimeWorkflowReleaseValidationResult result = service.validate(workflow);
        assertTrue(result.valid(), () -> result.errors().toString());
    }

    @Test
    void rejectsRetryOnNonRetryableNodeAndHttpNonIdempotentWithoutFlag() {
        RuntimeWorkflowReleaseValidationService service = service(mock(RuntimeControlCatalogClient.class));
        RuntimeWorkflowDefinitionEntity assignRetry = workflow("""
                {"entryNodeId":"assign","exitNodeIds":["answer"],"nodes":[
                  {"id":"assign","type":"VARIABLE_ASSIGN","retry":{"enabled":true,"maxAttempts":2},
                   "config":{"assignments":{"var.x":"input"}}},
                  {"id":"answer","type":"ANSWER","config":{"template":"x"}}
                ],"edges":[{"from":"assign","to":"answer","condition":"always"}]}
                """);
        assertTrue(hasError(service.validate(assignRetry), "GRAPH_RETRY_NOT_ALLOWED"));

        RuntimeWorkflowDefinitionEntity postRetry = workflow("""
                {"entryNodeId":"http","exitNodeIds":["answer"],"nodes":[
                  {"id":"http","type":"HTTP_REQUEST","retry":{"enabled":true,"maxAttempts":2},
                   "config":{"method":"POST","url":"https://example.com","bodyType":"json","body":"{}"}},
                  {"id":"answer","type":"ANSWER","config":{"template":"x"}}
                ],"edges":[{"from":"http","to":"answer","condition":"always"}]}
                """);
        assertTrue(hasError(service.validate(postRetry), "GRAPH_HTTP_RETRY_NON_IDEMPOTENT"));
    }

    @Test
    void rejectsAggregateDuplicateNameAndKnowledgeEmptyCodes() {
        RuntimeWorkflowReleaseValidationService service = service(mock(RuntimeControlCatalogClient.class));
        RuntimeWorkflowDefinitionEntity dup = workflow("""
                {"entryNodeId":"agg","exitNodeIds":["answer"],"nodes":[
                  {"id":"agg","type":"VARIABLE_AGGREGATOR","config":{"mode":"object",
                    "items":[{"name":"a","source":"input"},{"name":"a","source":"input"}]}},
                  {"id":"answer","type":"ANSWER","config":{"template":"x"}}
                ],"edges":[{"from":"agg","to":"answer","condition":"always"}]}
                """);
        assertTrue(hasError(service.validate(dup), "GRAPH_AGGREGATE_ITEM_NAME_DUPLICATE"));

        RuntimeWorkflowDefinitionEntity emptyKb = workflow("""
                {"entryNodeId":"kb","exitNodeIds":["answer"],"nodes":[
                  {"id":"kb","type":"KNOWLEDGE_RETRIEVAL","config":{"knowledgeBaseCodes":[],"query":"input"}},
                  {"id":"answer","type":"ANSWER","config":{"template":"x"}}
                ],"edges":[{"from":"kb","to":"answer","condition":"always"}]}
                """);
        assertTrue(hasError(service.validate(emptyKb), "GRAPH_KNOWLEDGE_BASE_REQUIRED"));
    }

    @Test
    void rejectsFallbackSelfLoop() {
        RuntimeWorkflowReleaseValidationService service = service(mock(RuntimeControlCatalogClient.class));
        RuntimeWorkflowDefinitionEntity workflow = workflow("""
                {"entryNodeId":"http","exitNodeIds":["answer"],"nodes":[
                  {"id":"http","type":"HTTP_REQUEST",
                   "errorPolicy":{"strategy":"FALLBACK","fallbackNodeId":"http"},
                   "config":{"method":"GET","url":"https://example.com"}},
                  {"id":"answer","type":"ANSWER","config":{"template":"x"}}
                ],"edges":[{"from":"http","to":"answer","condition":"always"}]}
                """);
        assertTrue(hasError(service.validate(workflow), "GRAPH_FALLBACK_SELF")
                || hasError(service.validate(workflow), "GRAPH_ERROR_FALLBACK_SELF"));
    }

    private RuntimeWorkflowReleaseValidationService service(RuntimeControlCatalogClient client) {
        return new RuntimeWorkflowReleaseValidationService(client, new ObjectMapper());
    }

    private RuntimeWorkflowReleaseValidationService openInteractionService() {
        RuntimeWorkflowNodeCapabilityRegistry real = new RuntimeWorkflowNodeCapabilityRegistry();
        RuntimeWorkflowNodeCapabilityRegistry registry = mock(RuntimeWorkflowNodeCapabilityRegistry.class);
        RuntimeWorkflowNodeCapabilityDescriptor open = new RuntimeWorkflowNodeCapabilityDescriptor(
                "INTERACTION",
                "interaction",
                "interaction",
                "CONTROL",
                false,
                WorkflowNodeMaturity.BETA,
                true,
                true,
                true,
                true,
                List.of(
                        "COLLECT_INPUT",
                        "PRESENT_OUTPUT",
                        "USER_CHOICE",
                        "CONFIRM_ACTION",
                        "REVIEW_EDIT",
                        "CUSTOM"),
                null);
        when(registry.isPublishable(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyMap())).thenReturn(true);
        when(registry.find(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            Object arg = invocation.getArgument(0);
            String type = arg == null ? "" : String.valueOf(arg);
            if ("INTERACTION".equalsIgnoreCase(type.trim())) {
                return java.util.Optional.of(open);
            }
            return real.find(type);
        });
        return new RuntimeWorkflowReleaseValidationService(
                mock(RuntimeControlCatalogClient.class), new ObjectMapper(), registry);
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
        entity.setExecutionEngine("GRAPH_SPEC");
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
                  "entryNodeId":"classifier",
                  "exitNodeIds":["classifier"]
                }
                """.formatted(strategy));
    }

    private RuntimeControlCatalogClient.PageActionCatalogEntry activePageAction() {
        return new RuntimeControlCatalogClient.PageActionCatalogEntry("demo", "orders", "open", "ACTIVE");
    }

    private RuntimeControlCatalogClient.PageActionCatalogEntry pageAction(
            String actionKey,
            Map<String, Object> inputSchema,
            Map<String, Object> outputSchema) {
        return new RuntimeControlCatalogClient.PageActionCatalogEntry(
                null,
                "demo",
                "orders",
                actionKey,
                actionKey,
                null,
                "READ",
                false,
                null,
                inputSchema,
                outputSchema,
                Map.of(),
                List.of(),
                null,
                Map.of(),
                "ACTIVE");
    }
}
