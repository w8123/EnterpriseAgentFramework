package com.enterprise.ai.runtime.workflow;

import com.enterprise.ai.runtime.client.control.RuntimeControlCatalogClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class RuntimeWorkflowParallelReleaseValidationTest {

    private final RuntimeWorkflowReleaseValidationService service =
            new RuntimeWorkflowReleaseValidationService(mock(RuntimeControlCatalogClient.class), new ObjectMapper());

    @Test
    void acceptsForkThatMeetsAtOneJoin() {
        RuntimeWorkflowReleaseValidationResult result = service.validate(workflow("""
                {"schemaVersion":2,"entryNodeId":"fork","exitNodeIds":["answer"],
                 "nodes":[
                   {"id":"fork","type":"PARALLEL","config":{"maxConcurrency":2}},
                   {"id":"a","type":"TEMPLATE","config":{"template":"a"}},
                   {"id":"b","type":"TEMPLATE","config":{"template":"b"}},
                   {"id":"answer","type":"ANSWER","config":{"template":"done"}}],
                 "edges":[
                   {"from":"fork","to":"a","condition":"always"},
                   {"from":"fork","to":"b","condition":"always"},
                   {"from":"a","to":"answer","condition":"always"},
                   {"from":"b","to":"answer","condition":"always"}]}
                """));
        assertTrue(result.errors().stream().noneMatch(item -> item.code().startsWith("GRAPH_PARALLEL")),
                () -> result.errors().toString());
        assertFalse(hasError(result, "GRAPH_NODE_NOT_PUBLISHABLE"));
        assertFalse(hasError(result, "GRAPH_NODE_RUNTIME_UNSUPPORTED"));
    }

    @Test
    void rejectsAnswerInsideBranchAndMissingJoin() {
        RuntimeWorkflowReleaseValidationResult result = service.validate(workflow("""
                {"schemaVersion":2,"entryNodeId":"fork","exitNodeIds":["a","b"],
                 "nodes":[
                   {"id":"fork","type":"PARALLEL","config":{}},
                   {"id":"a","type":"ANSWER","config":{"template":"a"}},
                   {"id":"b","type":"ANSWER","config":{"template":"b"}}],
                 "edges":[{"from":"fork","to":"a"},{"from":"fork","to":"b"}]}
                """));
        assertTrue(hasError(result, "GRAPH_PARALLEL_JOIN_MISSING"), () -> result.errors().toString());
    }

    @Test
    void rejectsSingleBranchAndBadConcurrency() {
        RuntimeWorkflowReleaseValidationResult result = service.validate(workflow("""
                {"schemaVersion":2,"entryNodeId":"fork","exitNodeIds":["answer"],
                 "nodes":[
                   {"id":"fork","type":"PARALLEL","config":{"maxConcurrency":9}},
                   {"id":"answer","type":"ANSWER","config":{"template":"done"}}],
                 "edges":[{"from":"fork","to":"answer"}]}
                """));
        assertTrue(hasError(result, "GRAPH_PARALLEL_BRANCH_COUNT_INVALID"));
        assertTrue(hasError(result, "GRAPH_PARALLEL_CONCURRENCY_INVALID"));
    }

    @Test
    void rejectsInteractionInsideBranch() {
        RuntimeWorkflowReleaseValidationResult result = service.validate(workflow("""
                {"schemaVersion":2,"entryNodeId":"fork","exitNodeIds":["answer"],
                 "nodes":[
                   {"id":"fork","type":"PARALLEL","config":{}},
                   {"id":"a","type":"TEMPLATE","config":{"template":"a"}},
                   {"id":"ask","type":"USER_INPUT","config":{}},
                   {"id":"answer","type":"ANSWER","config":{"template":"done"}}],
                 "edges":[
                   {"from":"fork","to":"a"},{"from":"fork","to":"ask"},
                   {"from":"a","to":"answer"},{"from":"ask","to":"answer"}]}
                """));
        assertTrue(hasError(result, "GRAPH_PARALLEL_BRANCH_FORBIDDEN"));
    }

    @Test
    void validatesWhileLoopAndTimeout() {
        RuntimeWorkflowReleaseValidationResult missingBreak = service.validate(workflow("""
                {"schemaVersion":2,"entryNodeId":"loop","exitNodeIds":["answer"],
                 "nodes":[
                   {"id":"loop","type":"LOOP","config":{"mode":"WHILE","maxIterations":5,"parallelism":2,
                     "bodyEntry":"t","bodyExit":"t","bodyNodeIds":["t"]}},
                   {"id":"t","type":"TEMPLATE","config":{"template":"x"}},
                   {"id":"answer","type":"ANSWER","config":{"template":"done"},"errorPolicy":{"timeoutMs":5000}}],
                 "edges":[{"from":"loop","to":"answer","condition":"always"}]}
                """));
        assertTrue(hasError(missingBreak, "GRAPH_LOOP_BREAK_CONDITION_REQUIRED"));
        assertTrue(hasError(missingBreak, "GRAPH_LOOP_PARALLELISM_INVALID"));
        assertTrue(hasError(missingBreak, "GRAPH_TIMEOUT_NOT_ALLOWED"));
        assertFalse(hasError(missingBreak, "GRAPH_LOOP_COLLECTION_REQUIRED"));
    }

    private boolean hasError(RuntimeWorkflowReleaseValidationResult result, String code) {
        return result.errors().stream().anyMatch(item -> code.equals(item.code()));
    }

    private RuntimeWorkflowDefinitionEntity workflow(String graphSpecJson) {
        RuntimeWorkflowDefinitionEntity entity = new RuntimeWorkflowDefinitionEntity();
        entity.setId("wf-parallel");
        entity.setKeySlug("parallel-demo");
        entity.setName("Parallel Demo");
        entity.setProjectCode("demo");
        entity.setGraphSpecJson(graphSpecJson);
        return entity;
    }
}
