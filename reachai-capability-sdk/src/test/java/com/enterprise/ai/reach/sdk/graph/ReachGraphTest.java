package com.enterprise.ai.reach.sdk.graph;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReachGraphTest {

    @Test
    void buildsWorkflowGraphSpecForCentralRuntime() {
        ReachAgentGraph graph = ReachGraph.agent("contract-review")
                .name("合同审查助手")
                .description("审查合同风险")
                .modelInstanceId("default-qwen")
                .llm("classify")
                .nodeName("识别审查类型")
                .systemPrompt("判断用户需要审查合同风险、抽取条款还是生成意见")
                .outputAlias("intent")
                .capability("queryContract")
                .nodeName("查询合同")
                .qualifiedName("contract.query")
                .input("contractNo", ReachVars.input("contractNo"))
                .outputAlias("contract")
                .answer("final")
                .nodeName("最终回答")
                .from(ReachVars.var("contract"))
                .edge(ReachGraph.START, "classify")
                .edge("classify", "queryContract")
                .edge("queryContract", "final")
                .edge("final", ReachGraph.END)
                .build();

        assertEquals("contract-review", graph.getCode());
        assertEquals("GRAPH_SPEC", graph.getExecutionEngine());
        assertEquals("default-qwen", graph.getModelInstanceId());

        ReachGraphSpec spec = graph.getGraphSpec();
        assertEquals(2, spec.getSchemaVersion());
        assertEquals("classify", spec.getEntryNodeId());
        assertEquals("final", spec.getExitNodeIds().get(0));
        assertEquals(3, spec.getNodes().size());
        assertEquals(2, spec.getEdges().size());

        ReachGraphSpec.Node llm = spec.getNodes().get(0);
        assertEquals("LLM", llm.getType());
        assertEquals("default-qwen", llm.getConfig().get("modelInstanceId"));
        assertEquals("intent", llm.getConfig().get("outputAlias"));

        ReachGraphSpec.Node capability = spec.getNodes().get(1);
        assertEquals("TOOL", capability.getType());
        assertEquals("contract.query", capability.getRef().getQualifiedName());
        Map<?, ?> inputMapping = (Map<?, ?>) capability.getConfig().get("inputMapping");
        assertEquals("${input.contractNo}", inputMapping.get("contractNo"));
    }

    @Test
    void graphObjectsDoNotExposeLocalRuntimeExecutionApi() {
        assertNoMethodNamed(ReachAgentGraph.class, "run");
        assertNoMethodNamed(ReachAgentGraph.class, "execute");
        assertNoMethodNamed(ReachGraphSpec.class, "run");
        assertNoMethodNamed(ReachGraphSpec.class, "execute");
    }

    @Test
    void serializesGraphSpecUsingPlatformContractNames() throws Exception {
        ReachAgentGraph graph = ReachGraph.agent("contract-review")
                .name("合同审查助手")
                .modelInstanceId("default-qwen")
                .llm("classify")
                .systemPrompt("识别用户意图")
                .outputAlias("intent")
                .answer("final")
                .from(ReachVars.var("intent"))
                .edge(ReachGraph.START, "classify")
                .edge("classify", "final")
                .edge("final", ReachGraph.END)
                .build();

        String json = ReachGraphSerializer.toJson(graph.getGraphSpec());
        Map<String, Object> parsed = new ObjectMapper().readValue(json, new TypeReference<Map<String, Object>>() {
        });

        assertEquals(2, parsed.get("schemaVersion"));
        assertEquals("classify", parsed.get("entryNodeId"));
        assertEquals(Collections.singletonList("final"), parsed.get("exitNodeIds"));
        assertTrue(parsed.containsKey("nodes"));
        assertTrue(parsed.containsKey("edges"));
        assertFalse(parsed.containsKey("canvasJson"));

        List<?> edges = (List<?>) parsed.get("edges");
        Map<?, ?> firstEdge = (Map<?, ?>) edges.get(0);
        assertTrue(firstEdge.containsKey("from"));
        assertTrue(firstEdge.containsKey("to"));
    }

    private static void assertNoMethodNamed(Class<?> type, String methodName) {
        for (Method method : type.getMethods()) {
            assertFalse(methodName.equals(method.getName()), type.getName() + " exposes local runtime method " + methodName);
        }
        assertNotNull(type);
    }
}
