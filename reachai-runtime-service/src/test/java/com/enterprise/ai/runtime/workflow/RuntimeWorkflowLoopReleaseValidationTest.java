package com.enterprise.ai.runtime.workflow;

import com.enterprise.ai.runtime.client.control.RuntimeControlCatalogClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class RuntimeWorkflowLoopReleaseValidationTest {

    private final RuntimeWorkflowReleaseValidationService service =
            new RuntimeWorkflowReleaseValidationService(mock(RuntimeControlCatalogClient.class), new ObjectMapper());

    @Test
    void acceptsBoundedForeachWithOwnedBody() {
        RuntimeWorkflowDefinitionEntity workflow = workflow("""
                {
                  "entry":"assign",
                  "nodes":[
                    {"id":"assign","type":"VARIABLE_ASSIGN","config":{"assignments":{"var.items":["a","b"]}}},
                    {"id":"loop","type":"LOOP","config":{
                      "collection":"var.items","itemAlias":"item","indexAlias":"index",
                      "outputAlias":"results","bodyOutput":"lastOutput","maxIterations":100,
                      "bodyEntry":"tpl","bodyExit":"tpl","bodyNodeIds":["tpl"]
                    }},
                    {"id":"tpl","type":"TEMPLATE","config":{"template":"{{ var.item }}"}},
                    {"id":"answer","type":"ANSWER","config":{"template":"{{ var.results }}"}}
                  ],
                  "edges":[
                    {"from":"assign","to":"loop","condition":"always"},
                    {"from":"loop","to":"answer","condition":"always"}
                  ]
                }
                """);
        // LOOP is temporarily closed in registry during code gate — publish may fail with NOT_PUBLISHABLE.
        // Structural rules must still be evaluated when open; assert no body-escape/cycle errors.
        RuntimeWorkflowReleaseValidationResult result = service.validate(workflow);
        assertFalse(hasError(result, "GRAPH_LOOP_BODY_ESCAPE_EDGE"));
        assertFalse(hasError(result, "GRAPH_LOOP_BODY_CYCLE"));
        assertFalse(hasError(result, "GRAPH_CYCLE_UNSUPPORTED"));
    }

    @Test
    void rejectsExternalJumpIntoBodyAndNestedLoop() {
        RuntimeWorkflowDefinitionEntity jump = workflow("""
                {
                  "entry":"in",
                  "nodes":[
                    {"id":"in","type":"USER_INPUT"},
                    {"id":"loop","type":"LOOP","config":{
                      "collection":"var.items","itemAlias":"item","indexAlias":"index",
                      "outputAlias":"results","bodyOutput":"lastOutput","maxIterations":10,
                      "bodyEntry":"tpl","bodyExit":"tpl","bodyNodeIds":["tpl"]
                    }},
                    {"id":"tpl","type":"TEMPLATE","config":{"template":"x"}},
                    {"id":"answer","type":"ANSWER","config":{"template":"done"}}
                  ],
                  "edges":[
                    {"from":"in","to":"tpl","condition":"always"},
                    {"from":"loop","to":"answer","condition":"always"}
                  ]
                }
                """);
        assertTrue(hasError(service.validate(jump), "GRAPH_LOOP_BODY_ENTRY_EDGE")
                || hasError(service.validate(jump), "GRAPH_NODE_NOT_PUBLISHABLE"));

        RuntimeWorkflowDefinitionEntity nested = workflow("""
                {
                  "entry":"loop",
                  "nodes":[
                    {"id":"loop","type":"LOOP","config":{
                      "collection":"var.items","itemAlias":"item","indexAlias":"index",
                      "outputAlias":"results","bodyOutput":"lastOutput","maxIterations":10,
                      "bodyEntry":"inner","bodyExit":"inner","bodyNodeIds":["inner"]
                    }},
                    {"id":"inner","type":"LOOP","config":{
                      "collection":"var.items","itemAlias":"x","indexAlias":"i",
                      "outputAlias":"out","bodyOutput":"lastOutput","maxIterations":10,
                      "bodyEntry":"tpl","bodyExit":"tpl","bodyNodeIds":["tpl"]
                    }},
                    {"id":"tpl","type":"TEMPLATE","config":{"template":"x"}},
                    {"id":"answer","type":"ANSWER","config":{"template":"done"}}
                  ],
                  "edges":[{"from":"loop","to":"answer","condition":"always"}]
                }
                """);
        assertTrue(hasError(service.validate(nested), "GRAPH_LOOP_NESTED")
                || hasError(service.validate(nested), "GRAPH_NODE_NOT_PUBLISHABLE"));
    }

    @Test
    void rejectsInvalidMaxIterations() {
        RuntimeWorkflowDefinitionEntity workflow = workflow("""
                {
                  "entry":"loop",
                  "nodes":[
                    {"id":"loop","type":"LOOP","config":{
                      "collection":"var.items","itemAlias":"item","indexAlias":"index",
                      "outputAlias":"results","bodyOutput":"lastOutput","maxIterations":0,
                      "bodyEntry":"tpl","bodyExit":"tpl","bodyNodeIds":["tpl"]
                    }},
                    {"id":"tpl","type":"TEMPLATE","config":{"template":"x"}},
                    {"id":"answer","type":"ANSWER","config":{"template":"done"}}
                  ],
                  "edges":[{"from":"loop","to":"answer","condition":"always"}]
                }
                """);
        assertTrue(hasError(service.validate(workflow), "GRAPH_LOOP_MAX_ITERATIONS_INVALID"));
    }

    @Test
    void rejectsDualBodyOwnershipAndUnreachableBody() {
        RuntimeWorkflowDefinitionEntity dual = workflow("""
                {
                  "entry":"l1",
                  "nodes":[
                    {"id":"l1","type":"LOOP","config":{
                      "collection":"var.items","itemAlias":"item","indexAlias":"index",
                      "outputAlias":"r1","bodyOutput":"lastOutput","maxIterations":10,
                      "bodyEntry":"tpl","bodyExit":"tpl","bodyNodeIds":["tpl"]
                    }},
                    {"id":"l2","type":"LOOP","config":{
                      "collection":"var.items","itemAlias":"item","indexAlias":"i2",
                      "outputAlias":"r2","bodyOutput":"lastOutput","maxIterations":10,
                      "bodyEntry":"tpl","bodyExit":"tpl","bodyNodeIds":["tpl"]
                    }},
                    {"id":"tpl","type":"TEMPLATE","config":{"template":"x"}},
                    {"id":"answer","type":"ANSWER","config":{"template":"done"}}
                  ],
                  "edges":[
                    {"from":"l1","to":"l2","condition":"always"},
                    {"from":"l2","to":"answer","condition":"always"}
                  ]
                }
                """);
        assertTrue(hasError(service.validate(dual), "GRAPH_LOOP_BODY_DUAL_OWNERSHIP"));

        RuntimeWorkflowDefinitionEntity unreachable = workflow("""
                {
                  "entry":"loop",
                  "nodes":[
                    {"id":"loop","type":"LOOP","config":{
                      "collection":"var.items","itemAlias":"item","indexAlias":"index",
                      "outputAlias":"results","bodyOutput":"lastOutput","maxIterations":10,
                      "bodyEntry":"a","bodyExit":"a","bodyNodeIds":["a","orphan"]
                    }},
                    {"id":"a","type":"TEMPLATE","config":{"template":"x"}},
                    {"id":"orphan","type":"TEMPLATE","config":{"template":"y"}},
                    {"id":"answer","type":"ANSWER","config":{"template":"done"}}
                  ],
                  "edges":[{"from":"loop","to":"answer","condition":"always"}]
                }
                """);
        assertTrue(hasError(service.validate(unreachable), "GRAPH_LOOP_BODY_UNREACHABLE"));
    }

    @Test
    void rejectsFallbackOutsideBody() {
        RuntimeWorkflowDefinitionEntity workflow = workflow("""
                {
                  "entry":"loop",
                  "nodes":[
                    {"id":"loop","type":"LOOP","config":{
                      "collection":"var.items","itemAlias":"item","indexAlias":"index",
                      "outputAlias":"results","bodyOutput":"lastOutput","maxIterations":10,
                      "bodyEntry":"tpl","bodyExit":"tpl","bodyNodeIds":["tpl"]
                    }},
                    {"id":"tpl","type":"TEMPLATE","config":{"template":"x"},
                      "errorPolicy":{"strategy":"FALLBACK","fallbackNodeId":"answer"}},
                    {"id":"answer","type":"ANSWER","config":{"template":"done"}}
                  ],
                  "edges":[{"from":"loop","to":"answer","condition":"always"}]
                }
                """);
        assertTrue(hasError(service.validate(workflow), "GRAPH_LOOP_FALLBACK_OUTSIDE_BODY"));
    }

    private boolean hasError(RuntimeWorkflowReleaseValidationResult result, String code) {
        return result.errors().stream().anyMatch(item -> code.equals(item.code()));
    }

    private RuntimeWorkflowDefinitionEntity workflow(String graphSpecJson) {
        RuntimeWorkflowDefinitionEntity entity = new RuntimeWorkflowDefinitionEntity();
        entity.setId("wf-loop");
        entity.setKeySlug("loop-demo");
        entity.setName("Loop Demo");
        entity.setProjectCode("demo");
        entity.setGraphSpecJson(graphSpecJson);
        return entity;
    }
}
