package com.enterprise.ai.agent.studio;

import com.enterprise.ai.agent.graph.AgentGraphSpec;
import com.enterprise.ai.agent.llm.LlmService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmWorkflowDraftGeneratorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void generatesContractWorkflowFromModelDraft() {
        LlmService llmService = mock(LlmService.class);
        when(llmService.chat(anyString(), anyString(), anyString())).thenReturn("""
                {
                  "summary": "合同审批流程",
                  "nodes": [
                    {
                      "id": "contract_input",
                      "kind": "userInput",
                      "label": "合同信息录入",
                      "description": "收集合同类型、金额、相对方和正文",
                      "config": {
                        "outputAlias": "params",
                        "fields": [
                          { "name": "contractType", "type": "string", "required": true, "description": "合同类型", "source": "input.contractType" },
                          { "name": "contractText", "type": "string", "required": true, "description": "合同正文", "source": "input.contractText" }
                        ]
                      }
                    },
                    {
                      "id": "risk_review",
                      "kind": "llm",
                      "label": "合同风险审查",
                      "description": "识别合同风险并输出审查意见",
                      "config": {
                        "systemPrompt": "你是合同审查助手，输出风险点和修改建议。",
                        "userPrompt": "{{ params.contractText }}",
                        "outputAlias": "risk_report"
                      }
                    },
                    {
                      "id": "legal_approval",
                      "kind": "approval",
                      "label": "法务审批",
                      "description": "等待法务确认风险审查结果",
                      "config": {
                        "title": "法务审批",
                        "prompt": "{{ risk_report }}",
                        "approvers": ["legal"]
                      }
                    },
                    {
                      "id": "final_answer",
                      "kind": "answer",
                      "label": "回复申请人",
                      "config": {
                        "template": "合同已完成审查，审批结果：{{ lastOutput }}"
                      }
                    }
                  ],
                  "edges": [
                    { "from": "START", "to": "contract_input", "condition": "always" },
                    { "from": "contract_input", "to": "risk_review", "condition": "always" },
                    { "from": "risk_review", "to": "legal_approval", "condition": "always" },
                    { "from": "legal_approval", "to": "final_answer", "condition": "approved", "sourceHandle": "approved" },
                    { "from": "final_answer", "to": "END", "condition": "always" }
                  ]
                }
                """);
        LlmWorkflowDraftGenerator generator = new LlmWorkflowDraftGenerator(objectMapper, llmService);

        WorkflowDraftGenerationResult result = generator.generate(WorkflowDraftGenerationRequest.builder()
                .agentId("agent-contract")
                .agentName("合同助手")
                .requirement("生成合同起草、风险审查、法务审批和回复申请人的流程")
                .projectCode("legal")
                .modelInstanceId("qwen-plus-default")
                .build());

        assertEquals("LLM_DRAFT", result.provider());
        assertEquals(List.of(), result.validationErrors());
        assertEquals(List.of(), result.placeholderNodes());
        assertNotNull(result.graphSpec());
        assertEquals("contract_input", result.graphSpec().getEntry());
        assertTrue(result.graphSpec().getFinish().contains("final_answer"));
        assertTrue(result.graphSpec().getNodes().stream().anyMatch(node ->
                "USER_INPUT".equals(node.getType()) && "contract_input".equals(node.getId())));
        assertTrue(result.graphSpec().getNodes().stream().anyMatch(node ->
                "LLM".equals(node.getType()) && "risk_review".equals(node.getId())
                        && "qwen-plus-default".equals(node.getConfig().get("modelInstanceId"))));
        assertTrue(result.graphSpec().getNodes().stream().anyMatch(node ->
                "HUMAN_APPROVAL".equals(node.getType()) && "legal_approval".equals(node.getId())));
        assertTrue(result.graphSpec().getEdges().stream().anyMatch(edge ->
                "legal_approval".equals(edge.getFrom())
                        && "final_answer".equals(edge.getTo())
                        && "approved".equals(edge.getCondition())));
        assertCanvasContainsNode(result, "risk_review", "llm");
        verify(llmService).chat(anyString(), anyString(), anyString());
    }

    @Test
    void returnsValidationErrorWhenModelDoesNotReturnJson() {
        LlmService llmService = mock(LlmService.class);
        when(llmService.chat(anyString(), anyString(), anyString())).thenReturn("我会帮你生成流程，但这里没有 JSON。");
        LlmWorkflowDraftGenerator generator = new LlmWorkflowDraftGenerator(objectMapper, llmService);

        WorkflowDraftGenerationResult result = generator.generate(WorkflowDraftGenerationRequest.builder()
                .agentName("合同助手")
                .requirement("生成合同审批流程")
                .modelInstanceId("model-1")
                .build());

        assertEquals("LLM_DRAFT", result.provider());
        assertTrue(result.validationErrors().stream().anyMatch(item -> item.contains("JSON")));
        assertTrue(result.graphSpec().getNodes().isEmpty());
        assertEquals(2, canvasNodes(result).size());
    }

    @Test
    void convertsUnknownToolReferenceToPlaceholderNode() {
        LlmService llmService = mock(LlmService.class);
        when(llmService.chat(anyString(), anyString(), anyString())).thenReturn("""
                {
                  "summary": "合同归档流程",
                  "nodes": [
                    { "id": "contract_input", "kind": "userInput", "label": "合同输入", "config": { "fields": [{ "name": "contractNo", "type": "string" }] } },
                    { "id": "archive_contract", "kind": "tool", "label": "合同归档", "config": { "ref": "archive_contract_tool", "inputMapping": { "contractNo": "params.contractNo" } } },
                    { "id": "reply", "kind": "answer", "label": "回复", "config": { "template": "已提交归档" } }
                  ],
                  "edges": [
                    { "from": "START", "to": "contract_input" },
                    { "from": "contract_input", "to": "archive_contract" },
                    { "from": "archive_contract", "to": "reply" },
                    { "from": "reply", "to": "END" }
                  ]
                }
                """);
        LlmWorkflowDraftGenerator generator = new LlmWorkflowDraftGenerator(objectMapper, llmService);

        WorkflowDraftGenerationResult result = generator.generate(WorkflowDraftGenerationRequest.builder()
                .agentName("合同助手")
                .requirement("生成合同归档流程")
                .modelInstanceId("model-1")
                .tools(List.of(WorkflowDraftResource.builder()
                        .kind("TOOL")
                        .name("query_contract")
                        .qualifiedName("legal:query_contract")
                        .description("查询合同")
                        .build()))
                .build());

        assertFalse(result.placeholderNodes().isEmpty());
        assertEquals("archive_contract", result.placeholderNodes().get(0).nodeId());
        AgentGraphSpec.Node placeholder = result.graphSpec().getNodes().stream()
                .filter(node -> "archive_contract".equals(node.getId()))
                .findFirst()
                .orElseThrow();
        assertEquals(Boolean.TRUE, placeholder.getConfig().get("needsConfiguration"));
        assertTrue(result.warnings().stream().anyMatch(item -> item.contains("archive_contract_tool")));
    }

    private void assertCanvasContainsNode(WorkflowDraftGenerationResult result, String id, String kind) {
        Map<String, Object> node = canvasNodes(result).stream()
                .filter(item -> id.equals(item.get("id")))
                .findFirst()
                .orElseThrow();
        assertEquals(kind, node.get("type"));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> canvasNodes(WorkflowDraftGenerationResult result) {
        return (List<Map<String, Object>>) (List<?>) result.canvasSnapshot().get("nodes");
    }
}
