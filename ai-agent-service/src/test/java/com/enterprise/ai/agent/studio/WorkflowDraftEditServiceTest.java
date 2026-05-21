package com.enterprise.ai.agent.studio;

import com.enterprise.ai.agent.llm.LlmService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkflowDraftEditServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void appliesStructuredPatchAndReturnsPreviewGraph() {
        LlmService llmService = mock(LlmService.class);
        when(llmService.chat(anyString(), anyString(), anyString())).thenReturn("""
                {
                  "summary": "added logistics lookup",
                  "operations": [
                    {
                      "type": "ADD_NODE",
                      "reason": "query logistics after the order lookup",
                      "node": {
                        "id": "logistics_lookup",
                        "type": "tool",
                        "position": { "x": 520, "y": 180 },
                        "data": {
                          "label": "物流查询",
                          "kind": "tool",
                          "configVersion": 2,
                          "outputAlias": "logistics",
                          "toolConfig": { "ref": "queryLogistics", "inputMapping": { "orderId": "order.id" } }
                        }
                      }
                    },
                    {
                      "type": "ADD_EDGE",
                      "reason": "run logistics after order query succeeds",
                      "edge": { "id": "e-order-logistics", "source": "order_lookup", "target": "logistics_lookup", "condition": "success" }
                    }
                  ]
                }
                """);

        WorkflowDraftEditService service = new WorkflowDraftEditService(objectMapper, llmService);
        WorkflowDraftEditRequest request = baseRequest();
        request.setInstruction("在订单查询后增加物流查询");
        request.setModelInstanceId("model-1");

        WorkflowDraftEditResult result = service.edit(request);

        assertEquals("added logistics lookup", result.summary());
        assertEquals(2, result.operations().size());
        assertEquals(List.of(), result.validationErrors());
        assertEquals(5, ((List<?>) result.canvasSnapshot().get("nodes")).size());
        assertEquals(4, ((List<?>) result.canvasSnapshot().get("edges")).size());
        assertEquals("TOOL", result.graphSpec().getNodes().stream()
                .filter(node -> "logistics_lookup".equals(node.getId()))
                .findFirst()
                .orElseThrow()
                .getType());
        assertTrue(result.graphSpec().getEdges().stream().anyMatch(edge ->
                "order_lookup".equals(edge.getFrom()) && "logistics_lookup".equals(edge.getTo())));
    }

    @Test
    void updatesAnswerTemplateFromTopLevelConfigPatch() {
        LlmService llmService = mock(LlmService.class);
        when(llmService.chat(anyString(), anyString(), anyString())).thenReturn("""
                {
                  "summary": "make fallback reply warmer",
                  "operations": [
                    {
                      "type": "UPDATE_NODE",
                      "nodeId": "reply",
                      "reason": "replace blunt fallback copy",
                      "patch": {
                        "config": {
                          "template": "Sorry, I cannot answer that yet. I can help with order questions."
                        }
                      }
                    }
                  ]
                }
                """);

        WorkflowDraftEditService service = new WorkflowDraftEditService(objectMapper, llmService);
        WorkflowDraftEditRequest request = baseRequest();
        request.setInstruction("make the selected answer node warmer");
        request.setModelInstanceId("model-1");

        WorkflowDraftEditResult result = service.edit(request);

        assertEquals(List.of(), result.validationErrors());
        Map<String, Object> reply = findSnapshotNode(result, "reply");
        Map<String, Object> data = map(reply.get("data"));
        Map<String, Object> answerConfig = map(data.get("answerConfig"));
        assertEquals("Sorry, I cannot answer that yet. I can help with order questions.", answerConfig.get("template"));
        assertEquals("Sorry, I cannot answer that yet. I can help with order questions.", data.get("template"));
        assertEquals("Sorry, I cannot answer that yet. I can help with order questions.", result.graphSpec().getNodes().stream()
                .filter(node -> "reply".equals(node.getId()))
                .findFirst()
                .orElseThrow()
                .getConfig()
                .get("template"));
    }

    @Test
    void rejectsDeletingBoundaryNodesWithoutChangingCanvas() {
        LlmService llmService = mock(LlmService.class);
        when(llmService.chat(anyString(), anyString(), anyString())).thenReturn("""
                {
                  "summary": "delete start",
                  "operations": [
                    { "type": "DELETE_NODE", "nodeId": "start", "reason": "user asked to simplify" }
                  ]
                }
                """);

        WorkflowDraftEditService service = new WorkflowDraftEditService(objectMapper, llmService);
        WorkflowDraftEditRequest request = baseRequest();
        request.setInstruction("删除开始节点");
        request.setModelInstanceId("model-1");

        WorkflowDraftEditResult result = service.edit(request);

        assertTrue(result.validationErrors().stream().anyMatch(item -> item.contains("start/end")));
        assertEquals(4, ((List<?>) result.canvasSnapshot().get("nodes")).size());
        assertEquals(3, ((List<?>) result.canvasSnapshot().get("edges")).size());
    }

    @Test
    void returnsValidationErrorWhenModelDoesNotReturnJson() {
        LlmService llmService = mock(LlmService.class);
        when(llmService.chat(anyString(), anyString(), anyString())).thenReturn("我会帮你修改流程。");

        WorkflowDraftEditService service = new WorkflowDraftEditService(objectMapper, llmService);
        WorkflowDraftEditRequest request = baseRequest();
        request.setInstruction("增加一个节点");
        request.setModelInstanceId("model-1");

        WorkflowDraftEditResult result = service.edit(request);

        assertTrue(result.validationErrors().stream().anyMatch(item -> item.contains("JSON")));
        assertEquals(4, ((List<?>) result.canvasSnapshot().get("nodes")).size());
        assertEquals(3, ((List<?>) result.canvasSnapshot().get("edges")).size());
    }

    private WorkflowDraftEditRequest baseRequest() {
        WorkflowDraftEditRequest request = new WorkflowDraftEditRequest();
        request.setAgentId("agent-1");
        request.setAgentName("订单助手");
        request.setCurrentCanvas(canvas());
        request.setSelectedNodeIds(List.of("order_lookup"));
        request.setSelectedEdgeIds(List.of());
        return request;
    }

    private Map<String, Object> canvas() {
        List<Map<String, Object>> nodes = new ArrayList<>();
        nodes.add(node("start", "start", "开始", 40, 180, Map.of()));
        nodes.add(node("order_lookup", "tool", "订单查询", 280, 180, Map.of(
                "outputAlias", "order",
                "toolConfig", Map.of("ref", "queryOrder", "inputMapping", Map.of("orderId", "params.orderId"))
        )));
        nodes.add(node("reply", "answer", "回复用户", 520, 180, Map.of(
                "answerConfig", Map.of("template", "{{ lastOutput }}")
        )));
        nodes.add(node("end", "end", "结束", 760, 180, Map.of()));

        List<Map<String, Object>> edges = List.of(
                edge("e-start-order", "start", "order_lookup", "always"),
                edge("e-order-reply", "order_lookup", "reply", "success"),
                edge("e-reply-end", "reply", "end", "always")
        );
        return new LinkedHashMap<>(Map.of("version", 2, "nodes", nodes, "edges", edges, "graphCode", "order_agent"));
    }

    private Map<String, Object> node(String id, String kind, String label, int x, int y, Map<String, Object> extra) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("label", label);
        data.put("kind", kind);
        data.put("configVersion", 2);
        data.putAll(extra);
        return new LinkedHashMap<>(Map.of(
                "id", id,
                "type", kind,
                "position", Map.of("x", x, "y", y),
                "data", data
        ));
    }

    private Map<String, Object> edge(String id, String source, String target, String condition) {
        return new LinkedHashMap<>(Map.of(
                "id", id,
                "source", source,
                "target", target,
                "condition", condition
        ));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> findSnapshotNode(WorkflowDraftEditResult result, String id) {
        return ((List<Map<String, Object>>) (List<?>) result.canvasSnapshot().get("nodes")).stream()
                .filter(node -> id.equals(node.get("id")))
                .findFirst()
                .orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }
}
