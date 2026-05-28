package com.enterprise.ai.spring.registry;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EafGraphTest {

    @Test
    @SuppressWarnings("unchecked")
    void builderEmitsPlatformGraphSpec() {
        EafAgentGraph graph = EafGraph.agent("order_assistant")
                .name("Order Assistant")
                .modelInstanceId("llm-1")
                .systemPrompt("Help users with order questions.")
                .llm("classify")
                .llmPrompt("Classify orders.", "Return JSON for {{ input }}")
                .modelParam("temperature", 0.2)
                .llmOutputField("intent", "string", true, null)
                .llmOutputField("confidence", "number", false, 0.5)
                .tool("queryOrder")
                .ref("order-service:queryOrder")
                .nodeDescription("Query order details from order service.")
                .position(520, 260)
                .inputPort("orderNo", "string", true)
                .input("orderNo", "$.message.orderNo")
                .outputAlias("order")
                .retry(3, 500)
                .onError("continue")
                .edge("classify", "queryOrder").label("classified").handles("success", "input").priority(10).when("success")
                .edge("queryOrder", EafGraph.END).always()
                .build();

        assertEquals("order_assistant", graph.code());
        assertEquals("LANGGRAPH4J", graph.runtimeType());
        Map<String, Object> spec = graph.graphSpec();
        assertEquals("classify", spec.get("entry"));
        assertEquals(List.of("queryOrder"), spec.get("finish"));
        assertEquals("LR", ((Map<String, Object>) spec.get("layout")).get("direction"));
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) spec.get("nodes");
        assertEquals(2, nodes.size());
        Map<String, Object> tool = nodes.get(1);
        assertEquals("TOOL", tool.get("type"));
        assertEquals("Query order details from order service.", tool.get("description"));
        assertEquals("queryOrder", ((Map<String, Object>) tool.get("ref")).get("name"));
        Map<String, Object> llmConfig = (Map<String, Object>) nodes.get(0).get("config");
        assertEquals("json", llmConfig.get("outputFormat"));
        assertEquals(0.2, ((Map<String, Object>) llmConfig.get("modelParams")).get("temperature"));
        assertEquals(2, ((List<Map<String, Object>>) llmConfig.get("outputSchema")).size());
        Map<String, Object> config = (Map<String, Object>) tool.get("config");
        assertEquals("order", config.get("outputAlias"));
        assertEquals("Query order details from order service.", config.get("description"));
        assertEquals(Map.of("x", 520, "y", 260), ((Map<String, Object>) config.get("ui")).get("position"));
        assertEquals(520, ((Map<String, Object>) tool.get("layout")).get("x"));
        assertEquals(3, ((Map<String, Object>) tool.get("retry")).get("maxAttempts"));
        assertEquals("CONTINUE", ((Map<String, Object>) tool.get("errorPolicy")).get("strategy"));
        assertEquals("orderNo", ((List<Map<String, Object>>) tool.get("inputs")).get(0).get("id"));
        assertEquals("$.message.orderNo", ((List<Map<String, Object>>) tool.get("inputs")).get(0).get("source"));
        assertEquals("order", ((List<Map<String, Object>>) tool.get("outputs")).get(0).get("id"));
        assertEquals("object", ((Map<String, Object>) tool.get("inputSchema")).get("type"));
        assertEquals("object", ((Map<String, Object>) tool.get("outputSchema")).get("type"));
        assertEquals("$.message.orderNo", ((Map<String, String>) config.get("inputMapping")).get("orderNo"));
        List<Map<String, Object>> edges = (List<Map<String, Object>>) spec.get("edges");
        Map<String, Object> classified = edges.stream()
                .filter(edge -> "classify".equals(edge.get("from")) && "queryOrder".equals(edge.get("to")))
                .findFirst()
                .orElseThrow();
        assertEquals("success", classified.get("sourceHandle"));
        assertEquals(10, classified.get("priority"));
        assertEquals("classified", ((Map<String, Object>) classified.get("layout")).get("label"));
    }

    @Test
    void rejectsDuplicateNodes() {
        EafGraph.Builder builder = EafGraph.agent("bad")
                .llm("node")
                .tool("node");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, builder::build);
        assertTrue(ex.getMessage().contains("duplicate"));
    }

    @Test
    void rejectsMissingLlm() {
        EafGraph.Builder builder = EafGraph.agent("bad")
                .tool("queryOrder");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, builder::build);
        assertTrue(ex.getMessage().contains("LLM"));
    }

    @Test
    void rejectsInvalidEdgeEndpoint() {
        EafGraph.Builder builder = EafGraph.agent("bad")
                .llm("llm")
                .edge("llm", "missing").always();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, builder::build);
        assertTrue(ex.getMessage().contains("missing"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void appliesModelInstanceIdToLlmRegardlessOfBuilderOrder() {
        EafAgentGraph graph = EafGraph.agent("order_assistant")
                .llm("planner")
                .modelInstanceId("llm-1")
                .build();

        Map<String, Object> spec = graph.graphSpec();
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) spec.get("nodes");
        Map<String, Object> config = (Map<String, Object>) nodes.get(0).get("config");
        assertEquals("llm-1", config.get("modelInstanceId"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void edgeBuilderCanBuildWithDefaultAlwaysCondition() {
        EafAgentGraph graph = EafGraph.agent("order_assistant")
                .llm("planner")
                .tool("queryOrder")
                .edge("planner", "queryOrder")
                .build();

        Map<String, Object> spec = graph.graphSpec();
        List<Map<String, Object>> edges = (List<Map<String, Object>>) spec.get("edges");
        assertTrue(edges.stream().anyMatch(edge ->
                "planner".equals(edge.get("from"))
                        && "queryOrder".equals(edge.get("to"))
                        && "always".equals(edge.get("condition"))));
    }

    @Test
    @SuppressWarnings("unchecked")
    void builderEmitsVariableTemplateAndConditionNodes() {
        EafAgentGraph graph = EafGraph.agent("workflow_assistant")
                .modelInstanceId("llm-1")
                .llm("planner")
                .variable("vars")
                .assign("customerId", "$input")
                .outputAlias("state")
                .ifElse("route")
                .template("reply", "Customer {{ state.customerId }}")
                .writeTemplateToAnswer(true)
                .edge("planner", "vars").always()
                .edge("vars", "route").always()
                .edge("route", "reply").when("not_empty")
                .build();

        List<Map<String, Object>> nodes = (List<Map<String, Object>>) graph.graphSpec().get("nodes");
        assertTrue(nodes.stream().anyMatch(node -> "VARIABLE_ASSIGN".equals(node.get("type"))));
        assertTrue(nodes.stream().anyMatch(node -> "IF_ELSE".equals(node.get("type"))));
        Map<String, Object> template = nodes.stream()
                .filter(node -> "TEMPLATE".equals(node.get("type")))
                .findFirst()
                .orElseThrow();
        Map<String, Object> config = (Map<String, Object>) template.get("config");
        assertEquals("Customer {{ state.customerId }}", config.get("template"));
        assertEquals(true, config.get("writeToAnswer"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void builderEmitsIntegrationAndRetrievalNodes() {
        EafAgentGraph graph = EafGraph.agent("integration_assistant")
                .modelInstanceId("llm-1")
                .llm("planner")
                .parameterExtract("extract")
                .parameterField("orderId", "integer", true, "lastOutput.orderId", 0)
                .outputAlias("params")
                .http("orderApi", "POST", "https://example.com/orders/{{ params.orderId }}")
                .header("Content-Type", "application/json")
                .body("{\"query\":\"{{ input }}\"}")
                .outputAlias("orderApi")
                .knowledgeRetrieval("knowledge", "kb_order")
                .query("input")
                .topK(3)
                .searchMode("hybrid")
                .rerankEnabled(true)
                .edge("planner", "extract").always()
                .edge("extract", "orderApi").always()
                .edge("orderApi", "knowledge").always()
                .build();

        List<Map<String, Object>> nodes = (List<Map<String, Object>>) graph.graphSpec().get("nodes");
        assertTrue(nodes.stream().anyMatch(node -> "PARAMETER_EXTRACT".equals(node.get("type"))));
        assertTrue(nodes.stream().anyMatch(node -> "HTTP_REQUEST".equals(node.get("type"))));
        Map<String, Object> knowledge = nodes.stream()
                .filter(node -> "KNOWLEDGE_RETRIEVAL".equals(node.get("type")))
                .findFirst()
                .orElseThrow();
        Map<String, Object> config = (Map<String, Object>) knowledge.get("config");
        assertEquals("kb_order", config.get("knowledgeBaseGroupId"));
        assertEquals(3, config.get("topK"));
        Map<String, Object> extract = nodes.stream()
                .filter(node -> "PARAMETER_EXTRACT".equals(node.get("type")))
                .findFirst()
                .map(node -> (Map<String, Object>) node.get("config"))
                .orElseThrow();
        List<Map<String, Object>> fields = (List<Map<String, Object>>) extract.get("fields");
        assertEquals("integer", fields.get(0).get("type"));
        assertEquals(0, fields.get(0).get("defaultValue"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void builderEmitsUserInputNodeAndSchemaVariables() {
        EafAgentGraph graph = EafGraph.agent("document_assistant")
                .modelInstanceId("llm-1")
                .userInput("user_input")
                .inputField("upload_document", "file", true, "Upload Document")
                .inputField("question", "string", false, "Question")
                .outputAlias("params")
                .documentExtract("extract")
                .documentField("text", EafVars.params("upload_document"))
                .llm("answer")
                .llmPrompt("Answer by document.", "Question: {{ params.question }}")
                .edge("user_input", "extract").always()
                .edge("extract", "answer").always()
                .build();

        Map<String, Object> spec = graph.graphSpec();
        Map<String, Object> inputSchema = (Map<String, Object>) spec.get("inputSchema");
        List<Map<String, Object>> fields = (List<Map<String, Object>>) inputSchema.get("fields");
        assertEquals("upload_document", fields.get(0).get("name"));
        assertEquals("file", fields.get(0).get("type"));

        List<Map<String, Object>> nodes = (List<Map<String, Object>>) spec.get("nodes");
        Map<String, Object> userInput = nodes.stream()
                .filter(node -> "USER_INPUT".equals(node.get("type")))
                .findFirst()
                .orElseThrow();
        Map<String, Object> config = (Map<String, Object>) userInput.get("config");
        assertEquals("params", config.get("outputAlias"));
        assertEquals(fields, config.get("fields"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void builderEmitsAdvancedWorkflowNodes() {
        EafAgentGraph graph = EafGraph.agent("advanced_assistant")
                .modelInstanceId("llm-1")
                .llm("planner")
                .intentClassifier("intent")
                .classifierInput("input")
                .intentClass("refund", "Refund", "refund", "退款")
                .code("transform")
                .output("intent", "lastRoute")
                .output("draft", "planner")
                .variableAggregator("summary")
                .aggregateItem("intent", "nodeOutput.transform.intent")
                .aggregateItem("draft", "nodeOutput.transform.draft")
                .outputAlias("summary")
                .humanApproval("approval", "确认 {{ summary.intent }}")
                .approver("ops")
                .loop("retry", 2)
                .breakWhen("success")
                .knowledgeWrite("write", "kb-orders")
                .knowledgeContent("const:确认记录", "summary.draft")
                .knowledgeTag("workflow")
                .documentExtract("extract")
                .documentField("orderId", "regex:order (\\d+)")
                .mcpCall("mcp", "filesystem.read_file")
                .mcpServer("local")
                .answer("reply", "Intent {{ summary.intent }}: {{ summary.draft }}")
                .edge("planner", "intent").always()
                .edge("intent", "transform").when("route:refund")
                .edge("transform", "summary").always()
                .edge("summary", "approval").always()
                .edge("approval", "retry").when("route:approved")
                .edge("retry", "write").when("route:continue")
                .edge("write", "extract").always()
                .edge("extract", "mcp").always()
                .edge("mcp", "reply").always()
                .build();

        List<Map<String, Object>> nodes = (List<Map<String, Object>>) graph.graphSpec().get("nodes");
        assertTrue(nodes.stream().anyMatch(node -> "INTENT_CLASSIFIER".equals(node.get("type"))));
        assertTrue(nodes.stream().anyMatch(node -> "CODE".equals(node.get("type"))));
        assertTrue(nodes.stream().anyMatch(node -> "VARIABLE_AGGREGATOR".equals(node.get("type"))));
        assertTrue(nodes.stream().anyMatch(node -> "HUMAN_APPROVAL".equals(node.get("type"))));
        assertTrue(nodes.stream().anyMatch(node -> "LOOP".equals(node.get("type"))));
        assertTrue(nodes.stream().anyMatch(node -> "KNOWLEDGE_WRITE".equals(node.get("type"))));
        assertTrue(nodes.stream().anyMatch(node -> "DOCUMENT_EXTRACT".equals(node.get("type"))));
        assertTrue(nodes.stream().anyMatch(node -> "MCP_CALL".equals(node.get("type"))));
        Map<String, Object> intent = nodes.stream()
                .filter(node -> "INTENT_CLASSIFIER".equals(node.get("type")))
                .findFirst()
                .orElseThrow();
        List<Map<String, Object>> intentOutputs = (List<Map<String, Object>>) intent.get("outputs");
        assertTrue(intentOutputs.stream().anyMatch(port -> "refund".equals(port.get("id"))));
        assertTrue(intentOutputs.stream().anyMatch(port -> "else".equals(port.get("id"))));
        Map<String, Object> answer = nodes.stream()
                .filter(node -> "ANSWER".equals(node.get("type")))
                .findFirst()
                .orElseThrow();
        assertEquals("Intent {{ summary.intent }}: {{ summary.draft }}",
                ((Map<String, Object>) answer.get("config")).get("template"));
        List<Map<String, Object>> edges = (List<Map<String, Object>>) graph.graphSpec().get("edges");
        Map<String, Object> classifierEdge = edges.stream()
                .filter(edge -> "intent".equals(edge.get("from")) && "transform".equals(edge.get("to")))
                .findFirst()
                .orElseThrow();
        assertEquals("refund", classifierEdge.get("sourceHandle"));
    }

    @Test
    void sdkNodeCatalogIncludesAdvancedNodeTypes() {
        assertEquals("INTENT_CLASSIFIER", EafGraphNodeType.normalize("classifier"));
        assertEquals("KNOWLEDGE_WRITE", EafGraphNodeType.normalize("knowledgeWrite"));
        assertEquals("PAGE_ACTION", EafGraphNodeType.normalize("pageAction"));
        assertTrue(EafGraph.supportedNodeTypes().contains("HUMAN_APPROVAL"));
        assertTrue(EafGraph.supportedNodeTypes().contains("MCP_CALL"));
        assertTrue(EafGraph.supportedNodeTypes().contains("PAGE_ACTION"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildsPageActionNode() {
        EafAgentGraph graph = EafGraph.agent("page_action_agent")
                .llm("planner")
                .variable("prepare")
                .assign("teamId", "const:TI_DEMO01")
                .pageAction("openDetail", "team.openDetail")
                .pageActionArg("teamId", "teamId")
                .pageActionConfirm(false)
                .edge("planner", "prepare").always()
                .edge("prepare", "openDetail").always()
                .build();

        List<Map<String, Object>> nodes = (List<Map<String, Object>>) graph.graphSpec().get("nodes");
        Map<String, Object> pageAction = nodes.stream()
                .filter(node -> "PAGE_ACTION".equals(node.get("type")))
                .findFirst()
                .orElseThrow();
        Map<String, Object> config = (Map<String, Object>) pageAction.get("config");
        assertEquals("team.openDetail", config.get("actionKey"));
        assertEquals(false, config.get("confirm"));
        assertEquals("teamId", ((Map<String, Object>) config.get("args")).get("teamId"));
    }

    @Test
    void classifierEdgesRequireConcreteRoute() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> EafGraph.agent("bad_classifier")
                .modelInstanceId("llm-1")
                .llm("planner")
                .intentClassifier("intent")
                .intentClass("refund", "Refund")
                .answer("reply", "{{ lastOutput }}")
                .edge("planner", "intent").always()
                .edge("intent", "reply").always()
                .build());

        assertTrue(error.getMessage().contains("intent classifier edge must use a class route"));
    }
}
