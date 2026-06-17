package com.enterprise.ai.agent.studio;

import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.agent.llm.LlmService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
        GraphSpec.Node placeholder = result.graphSpec().getNodes().stream()
                .filter(node -> "archive_contract".equals(node.getId()))
                .findFirst()
                .orElseThrow();
        assertEquals(Boolean.TRUE, placeholder.getConfig().get("needsConfiguration"));
        assertTrue(result.warnings().stream().anyMatch(item -> item.contains("archive_contract_tool")));
    }

    @Test
    void bindsSuppliedPageActionResourceToPageActionNode() {
        LlmService llmService = mock(LlmService.class);
        when(llmService.chat(anyString(), anyString(), anyString())).thenReturn("""
                {
                  "summary": "页面查询助手",
                  "nodes": [
                    { "id": "collect_query", "kind": "userInput", "label": "收集查询条件", "config": { "fields": [{ "name": "teamName", "type": "string" }] } },
                    { "id": "dispatch_page_query", "kind": "pageAction", "label": "触发页面查询", "config": { "ref": "qmssmp.teamArchive.search", "args": { "teamName": "{{ params.teamName }}" } } },
                    { "id": "reply", "kind": "answer", "label": "回复", "config": { "template": "已为你触发页面查询" } }
                  ],
                  "edges": [
                    { "from": "START", "to": "collect_query" },
                    { "from": "collect_query", "to": "dispatch_page_query" },
                    { "from": "dispatch_page_query", "to": "reply" },
                    { "from": "reply", "to": "END" }
                  ]
                }
                """);
        LlmWorkflowDraftGenerator generator = new LlmWorkflowDraftGenerator(objectMapper, llmService);

        WorkflowDraftGenerationResult result = generator.generate(WorkflowDraftGenerationRequest.builder()
                .agentName("班组页面助手")
                .requirement("根据用户输入触发班组档案页面查询")
                .projectCode("qmssmp-teams-construction-service")
                .modelInstanceId("model-1")
                .pageActions(List.of(WorkflowDraftResource.builder()
                        .kind("PAGE_ACTION")
                        .name("qmssmp.teamArchive.search")
                        .qualifiedName("teamArchive.list/qmssmp.teamArchive.search")
                        .projectCode("qmssmp-teams-construction-service")
                        .description("查询班组档案")
                        .metadata(Map.of(
                                "pageKey", "teamArchive.list",
                                "routePattern", "/teams/archive",
                                "confirmRequired", false,
                                "inputSchema", Map.of(
                                        "type", "object",
                                        "required", List.of("teamName"),
                                        "properties", Map.of("teamName", Map.of("type", "string"))),
                                "sampleArgs", Map.of("teamName", "一班")))
                        .build()))
                .build());

        assertEquals(List.of(), result.validationErrors());
        GraphSpec.Node pageAction = result.graphSpec().getNodes().stream()
                .filter(node -> "dispatch_page_query".equals(node.getId()))
                .findFirst()
                .orElseThrow();
        assertEquals("PAGE_ACTION", pageAction.getType());
        assertEquals("qmssmp-teams-construction-service", pageAction.getConfig().get("projectCode"));
        assertEquals("teamArchive.list", pageAction.getConfig().get("pageKey"));
        assertEquals("qmssmp.teamArchive.search", pageAction.getConfig().get("actionKey"));
        assertEquals("触发页面查询", pageAction.getConfig().get("title"));
        assertEquals(Boolean.FALSE, pageAction.getConfig().get("confirm"));
        assertEquals(Map.of("teamName", "{{ params.teamName }}"), pageAction.getConfig().get("args"));
        assertEquals("page_action_result", pageAction.getConfig().get("outputAlias"));
    }

    @Test
    void normalizesPageActionDraftWhenModelOmitsActionRefAndInventsExtractionAlias() {
        LlmService llmService = mock(LlmService.class);
        when(llmService.chat(anyString(), anyString(), anyString())).thenReturn("""
                {
                  "summary": "班组档案查询助手",
                  "nodes": [
                    { "id": "user_input", "kind": "userInput", "label": "用户输入", "config": { "fields": [{ "name": "question", "type": "string" }] } },
                    { "id": "参数提取", "kind": "llm", "label": "参数提取", "config": { "userPrompt": "从 {{ params.question }} 提取查询条件", "outputFormat": "json", "structuredOutput": true } },
                    {
                      "id": "execute_query",
                      "kind": "pageAction",
                      "label": "执行查询",
                      "config": {
                        "args": {
                          "teamName": "{{ extract_params_llm.result.teamName }}",
                          "managerName": "{{ extract_params_llm.result.managerName }}"
                        }
                      },
                      "inputs": [
                        { "id": "teamName", "name": "teamName", "type": "string", "source": "extract_params_llm.result.teamName" },
                        { "id": "managerName", "name": "managerName", "type": "string", "source": "extract_params_llm.result.managerName" }
                      ]
                    },
                    { "id": "reply", "kind": "answer", "label": "回复结果", "config": { "template": "已触发查询" } }
                  ],
                  "edges": [
                    { "from": "START", "to": "user_input" },
                    { "from": "user_input", "to": "参数提取" },
                    { "from": "参数提取", "to": "execute_query" },
                    { "from": "execute_query", "to": "reply" },
                    { "from": "reply", "to": "END" }
                  ]
                }
                """);
        LlmWorkflowDraftGenerator generator = new LlmWorkflowDraftGenerator(objectMapper, llmService);

        WorkflowDraftGenerationResult result = generator.generate(WorkflowDraftGenerationRequest.builder()
                .agentName("班组页面助手")
                .requirement("根据用户输入触发班组档案页面查询")
                .projectCode("qmssmp-teams-construction-service")
                .modelInstanceId("model-1")
                .pageActions(List.of(WorkflowDraftResource.builder()
                        .kind("PAGE_ACTION")
                        .name("qmssmp.teamArchive.search")
                        .qualifiedName("teamArchive.list/qmssmp.teamArchive.search")
                        .projectCode("qmssmp-teams-construction-service")
                        .description("查询班组档案")
                        .metadata(Map.of(
                                "pageKey", "teamArchive.list",
                                "routePattern", "/teams/archive",
                                "confirmRequired", false,
                                "inputSchema", Map.of(
                                        "type", "object",
                                        "required", List.of("teamName"),
                                        "properties", Map.of(
                                                "teamName", Map.of("type", "string"),
                                                "managerName", Map.of("type", "string"))),
                                "sampleArgs", Map.of("teamName", "一班")))
                        .build()))
                .build());

        assertEquals(List.of(), result.validationErrors());
        GraphSpec.Node extractNode = result.graphSpec().getNodes().stream()
                .filter(node -> "参数提取".equals(node.getId()))
                .findFirst()
                .orElseThrow();
        assertEquals("extract_params_llm", extractNode.getConfig().get("outputAlias"));

        GraphSpec.Node pageAction = result.graphSpec().getNodes().stream()
                .filter(node -> "execute_query".equals(node.getId()))
                .findFirst()
                .orElseThrow();
        assertEquals("qmssmp.teamArchive.search", pageAction.getConfig().get("actionKey"));
        assertEquals("teamArchive.list", pageAction.getConfig().get("pageKey"));
        assertEquals("extract_params_llm.result.teamName", pageAction.getInputs().get(0).getSource());
    }

    @Test
    void bindsMultiplePageActionsWhenModelOmitsActionRefs() {
        LlmService llmService = mock(LlmService.class);
        when(llmService.chat(anyString(), anyString(), anyString())).thenReturn("""
                {
                  "summary": "班组档案查询助手",
                  "nodes": [
                    { "id": "user_input", "kind": "userInput", "label": "用户输入", "config": { "fields": [{ "name": "question", "type": "string" }] } },
                    { "id": "extract_params", "kind": "llm", "label": "提取查询条件", "config": { "userPrompt": "{{ params.question }}", "outputFormat": "json", "structuredOutput": true } },
                    { "id": "set_filters", "kind": "pageAction", "label": "设置筛选条件", "config": { "args": { "teamName": "{{ extract_params.result.teamName }}" } } },
                    { "id": "execute_query", "kind": "pageAction", "label": "执行查询", "config": {} },
                    { "id": "reply", "kind": "answer", "label": "结果反馈", "config": { "template": "已触发查询" } }
                  ],
                  "edges": [
                    { "from": "START", "to": "user_input" },
                    { "from": "user_input", "to": "extract_params" },
                    { "from": "extract_params", "to": "set_filters" },
                    { "from": "set_filters", "to": "execute_query" },
                    { "from": "execute_query", "to": "reply" },
                    { "from": "reply", "to": "END" }
                  ]
                }
                """);
        LlmWorkflowDraftGenerator generator = new LlmWorkflowDraftGenerator(objectMapper, llmService);

        WorkflowDraftGenerationResult result = generator.generate(WorkflowDraftGenerationRequest.builder()
                .agentName("班组页面助手")
                .requirement("根据用户输入设置筛选条件并执行查询")
                .projectCode("qmssmp-teams-construction-service")
                .modelInstanceId("model-1")
                .pageActions(List.of(
                        pageActionResource("setFilters", "设置筛选条件"),
                        pageActionResource("search", "执行查询"),
                        pageActionResource("reset", "重置筛选")))
                .build());

        assertEquals(List.of(), result.validationErrors());
        assertEquals(List.of(), result.placeholderNodes());
        GraphSpec.Node setFilters = result.graphSpec().getNodes().stream()
                .filter(node -> "set_filters".equals(node.getId()))
                .findFirst()
                .orElseThrow();
        GraphSpec.Node executeQuery = result.graphSpec().getNodes().stream()
                .filter(node -> "execute_query".equals(node.getId()))
                .findFirst()
                .orElseThrow();
        assertEquals("setFilters", setFilters.getConfig().get("actionKey"));
        assertEquals("search", executeQuery.getConfig().get("actionKey"));
    }

    @Test
    void pageAssistantDraftRejectsExplicitPageActionThatWasNotSupplied() {
        LlmService llmService = mock(LlmService.class);
        when(llmService.chat(anyString(), anyString(), anyString())).thenReturn("""
                {
                  "summary": "班组档案查询助手",
                  "nodes": [
                    { "id": "user_input", "kind": "userInput", "label": "用户输入", "config": { "fields": [{ "name": "question", "type": "string" }] } },
                    { "id": "extract_filters", "kind": "llm", "label": "提取筛选条件", "config": { "userPrompt": "{{ params.question }}" } },
                    { "id": "set_filters", "kind": "pageAction", "label": "设置筛选条件", "config": { "actionKey": "setFilters", "args": { "managerName": "{{ extract_filters.result.managerName }}" } } },
                    { "id": "search", "kind": "pageAction", "label": "执行查询", "config": { "actionKey": "search" } },
                    { "id": "reply", "kind": "answer", "label": "结果反馈", "config": { "template": "已触发查询" } }
                  ],
                  "edges": [
                    { "from": "START", "to": "user_input" },
                    { "from": "user_input", "to": "extract_filters" },
                    { "from": "extract_filters", "to": "set_filters" },
                    { "from": "set_filters", "to": "search" },
                    { "from": "search", "to": "reply" },
                    { "from": "reply", "to": "END" }
                  ]
                }
                """);
        LlmWorkflowDraftGenerator generator = new LlmWorkflowDraftGenerator(objectMapper, llmService);

        WorkflowDraftGenerationResult result = generator.generate(WorkflowDraftGenerationRequest.builder()
                .agentName("班组页面助手")
                .requirement("根据用户输入设置筛选条件并执行查询")
                .projectCode("qmssmp-teams-construction-service")
                .draftScenario("PAGE_ASSISTANT")
                .modelInstanceId("model-1")
                .pageActions(List.of(
                        pageActionResource("search", "执行查询"),
                        pageActionResource("readTable", "读取表格")))
                .build());

        assertTrue(result.validationErrors().stream().anyMatch(error ->
                error.contains("setFilters") && error.contains("not supplied")));
        assertTrue(result.placeholderNodes().stream().anyMatch(node -> "set_filters".equals(node.nodeId())));
        GraphSpec.Node setFilters = result.graphSpec().getNodes().stream()
                .filter(node -> "set_filters".equals(node.getId()))
                .findFirst()
                .orElseThrow();
        assertFalse("search".equals(setFilters.getConfig().get("actionKey")),
                "missing setFilters must not be silently rebound to search");
    }

    @Test
    void pageAssistantPromptForbidsImplicitPageActions() {
        LlmService llmService = mock(LlmService.class);
        when(llmService.chat(anyString(), anyString(), anyString())).thenReturn("""
                {
                  "summary": "班组档案查询助手",
                  "nodes": [
                    { "id": "user_input", "kind": "userInput", "label": "用户输入", "config": { "fields": [{ "name": "question", "type": "string" }] } },
                    { "id": "search", "kind": "pageAction", "label": "执行查询", "config": { "actionKey": "search" } },
                    { "id": "reply", "kind": "answer", "label": "结果反馈", "config": { "template": "已触发查询" } }
                  ],
                  "edges": [
                    { "from": "START", "to": "user_input" },
                    { "from": "user_input", "to": "search" },
                    { "from": "search", "to": "reply" },
                    { "from": "reply", "to": "END" }
                  ]
                }
                """);
        LlmWorkflowDraftGenerator generator = new LlmWorkflowDraftGenerator(objectMapper, llmService);

        generator.generate(WorkflowDraftGenerationRequest.builder()
                .agentName("班组页面助手")
                .requirement("根据用户输入执行查询")
                .projectCode("qmssmp-teams-construction-service")
                .draftScenario("PAGE_ASSISTANT")
                .modelInstanceId("model-1")
                .pageActions(List.of(pageActionResource("search", "执行查询")))
                .build());

        ArgumentCaptor<String> systemPrompt = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);
        verify(llmService).chat(systemPrompt.capture(), userPrompt.capture(), anyString());
        assertTrue(systemPrompt.getValue().contains("Never invent or assume pageAction actionKeys"));
        assertTrue(systemPrompt.getValue().contains("Do not create setFilters unless pageActions contains setFilters"));
        assertTrue(userPrompt.getValue().contains("\"allowedActionKeys\" : [ \"search\" ]"));
        assertFalse(userPrompt.getValue().contains("\"set_filters\""));
    }

    @Test
    void pageAssistantDraftBackfillsSelectedPageActionsWhenModelOmitsThem() {
        LlmService llmService = mock(LlmService.class);
        when(llmService.chat(anyString(), anyString(), anyString())).thenReturn("""
                {
                  "summary": "班组档案查询助手",
                  "nodes": [
                    { "id": "user_input", "kind": "userInput", "label": "用户输入", "config": { "fields": [{ "name": "question", "type": "string" }] } },
                    { "id": "answer", "kind": "answer", "label": "答案", "config": { "template": "{{ lastOutput }}" } }
                  ],
                  "edges": [
                    { "from": "START", "to": "user_input" },
                    { "from": "user_input", "to": "answer" },
                    { "from": "answer", "to": "END" }
                  ]
                }
                """);
        LlmWorkflowDraftGenerator generator = new LlmWorkflowDraftGenerator(objectMapper, llmService);

        WorkflowDraftGenerationResult result = generator.generate(WorkflowDraftGenerationRequest.builder()
                .agentName("班组页面助手")
                .requirement("根据负责人筛选并查询班组档案")
                .projectCode("qmssmp-teams-construction-service")
                .draftScenario("PAGE_ASSISTANT")
                .modelInstanceId("model-1")
                .pageActions(List.of(
                        pageActionResource("setFilters", "设置筛选", Map.of("managerName", "张三")),
                        pageActionResource("search", "执行查询"),
                        pageActionResource("readTable", "读取表格")))
                .build());

        assertEquals(List.of(), result.validationErrors());
        assertTrue(result.placeholderNodes().isEmpty());
        assertTrue(result.graphSpec().getNodes().stream().anyMatch(node -> "extract_filters".equals(node.getId())));
        assertTrue(result.graphSpec().getNodes().stream().anyMatch(node -> "set_filters".equals(node.getId())
                && "setFilters".equals(node.getConfig().get("actionKey"))));
        assertTrue(result.graphSpec().getNodes().stream().anyMatch(node -> "search".equals(node.getId())
                && "search".equals(node.getConfig().get("actionKey"))));
        assertTrue(result.graphSpec().getNodes().stream().anyMatch(node -> "read_table".equals(node.getId())
                && "readTable".equals(node.getConfig().get("actionKey"))));
        assertTrue(result.graphSpec().getEdges().stream().anyMatch(edge ->
                "set_filters".equals(edge.getFrom()) && "search".equals(edge.getTo())));
        assertTrue(result.graphSpec().getEdges().stream().anyMatch(edge ->
                "search".equals(edge.getFrom()) && "read_table".equals(edge.getTo())));
    }

    @Test
    void normalizesStringPortAliasesInInputsAndOutputs() {
        LlmService llmService = mock(LlmService.class);
        when(llmService.chat(anyString(), anyString(), anyString())).thenReturn("""
                {
                  "summary": "页面查询助手",
                  "nodes": [
                    { "id": "user_input", "kind": "userInput", "label": "用户输入", "config": { "fields": [{ "name": "question", "type": "string" }] } },
                    { "id": "extract_params", "kind": "llm", "label": "提取筛选参数", "config": { "userPrompt": "{{ params.question }}" }, "outputs": ["query"] },
                    { "id": "set_filters", "kind": "pageAction", "label": "设置筛选条件", "config": { "actionKey": "setFilters" }, "inputs": ["query"] },
                    { "id": "reply", "kind": "answer", "label": "回复", "config": { "template": "完成" } }
                  ],
                  "edges": [
                    { "from": "START", "to": "user_input" },
                    { "from": "user_input", "to": "extract_params" },
                    { "from": "extract_params", "to": "set_filters" },
                    { "from": "set_filters", "to": "reply" },
                    { "from": "reply", "to": "END" }
                  ]
                }
                """);
        LlmWorkflowDraftGenerator generator = new LlmWorkflowDraftGenerator(objectMapper, llmService);

        WorkflowDraftGenerationResult result = generator.generate(WorkflowDraftGenerationRequest.builder()
                .agentName("页面助手")
                .requirement("提取参数并触发页面动作")
                .modelInstanceId("model-1")
                .build());

        assertEquals(List.of(), result.validationErrors());
        assertFalse(result.graphSpec().getNodes().isEmpty());
        assertTrue(result.graphSpec().getNodes().stream().anyMatch(node -> "extract_params".equals(node.getId())));
        assertTrue(result.graphSpec().getNodes().stream().anyMatch(node -> "set_filters".equals(node.getId())));
    }

    @Test
    void normalizesPageAssistantDraftWithEmptySetFiltersArgs() {
        LlmService llmService = mock(LlmService.class);
        when(llmService.chat(anyString(), anyString(), anyString())).thenReturn("""
                {
                  "summary": "部门管理页面助手",
                  "nodes": [
                    { "id": "user_input", "kind": "userInput", "label": "用户输入", "config": { "fields": [{ "name": "question", "type": "string" }] } },
                    { "id": "extract_filters", "kind": "llm", "label": "提取筛选条件", "config": { "userPrompt": "{{ params.question }}" } },
                    { "id": "set_filters", "kind": "pageAction", "label": "设置筛选", "config": { "ref": "setFilters", "args": {} } },
                    { "id": "search", "kind": "pageAction", "label": "执行查询", "config": { "ref": "search", "args": {} } },
                    { "id": "read_table", "kind": "pageAction", "label": "读取表格", "config": { "ref": "readTable", "args": {} } },
                    { "id": "answer", "kind": "answer", "label": "回复", "config": { "template": "{{ lastOutput }}" } }
                  ],
                  "edges": [
                    { "from": "START", "to": "user_input" },
                    { "from": "user_input", "to": "extract_filters" },
                    { "from": "extract_filters", "to": "set_filters" },
                    { "from": "set_filters", "to": "search" },
                    { "from": "search", "to": "read_table" },
                    { "from": "read_table", "to": "answer" },
                    { "from": "answer", "to": "END" }
                  ]
                }
                """);
        LlmWorkflowDraftGenerator generator = new LlmWorkflowDraftGenerator(objectMapper, llmService);

        WorkflowDraftGenerationResult result = generator.generate(WorkflowDraftGenerationRequest.builder()
                .agentName("部门管理助手")
                .requirement("提取筛选条件并查询部门列表")
                .draftScenario("PAGE_ASSISTANT")
                .modelInstanceId("model-1")
                .pageActions(List.of(
                        pageActionResource("setFilters", "设置筛选", Map.of(
                                "managerName", "张三",
                                "deptName", "研发部")),
                        pageActionResource("search", "执行查询"),
                        pageActionResource("readTable", "读取表格")))
                .build());

        assertEquals(List.of(), result.validationErrors());

        GraphSpec.Node extractNode = result.graphSpec().getNodes().stream()
                .filter(node -> "extract_filters".equals(node.getId()))
                .findFirst()
                .orElseThrow();
        assertEquals("extracted_filters", extractNode.getConfig().get("outputAlias"));
        assertEquals("json", extractNode.getConfig().get("outputFormat"));

        GraphSpec.Node setFilters = result.graphSpec().getNodes().stream()
                .filter(node -> "set_filters".equals(node.getId()))
                .findFirst()
                .orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> args = (Map<String, Object>) setFilters.getConfig().get("args");
        assertEquals("extracted_filters.managerName", args.get("managerName"));
        assertEquals("extracted_filters.deptName", args.get("deptName"));

        GraphSpec.Node answer = result.graphSpec().getNodes().stream()
                .filter(node -> "answer".equals(node.getId()))
                .findFirst()
                .orElseThrow();
        assertEquals("正在按你的条件查询页面数据，请稍候…", answer.getConfig().get("template"));

        assertTrue(result.warnings().stream().anyMatch(warning -> warning.contains("筛选提取 prompt")));
        assertTrue(result.warnings().stream().anyMatch(warning -> warning.contains("自动生成 args 映射")));
    }

    @Test
    void canvasEdgesOmitSemanticPortHandlesForLinearNodes() {
        LlmService llmService = mock(LlmService.class);
        when(llmService.chat(anyString(), anyString(), anyString())).thenReturn("""
                {
                  "summary": "页面查询助手",
                  "nodes": [
                    { "id": "user_input", "kind": "userInput", "label": "用户输入", "config": { "fields": [{ "name": "question", "type": "string" }] } },
                    { "id": "extract_params", "kind": "llm", "label": "提取筛选参数", "config": { "userPrompt": "{{ params.question }}" } },
                    { "id": "set_filters", "kind": "pageAction", "label": "设置筛选条件", "config": { "actionKey": "setFilters" } },
                    { "id": "reply", "kind": "answer", "label": "回复", "config": { "template": "完成" } }
                  ],
                  "edges": [
                    { "from": "START", "to": "user_input", "condition": "always" },
                    { "from": "user_input", "to": "extract_params", "condition": "always", "sourceHandle": "output", "targetHandle": "input" },
                    { "from": "extract_params", "to": "set_filters", "condition": "always", "sourceHandle": "output", "targetHandle": "filterParams" },
                    { "from": "set_filters", "to": "reply", "condition": "always" },
                    { "from": "reply", "to": "END", "condition": "always" }
                  ]
                }
                """);
        LlmWorkflowDraftGenerator generator = new LlmWorkflowDraftGenerator(objectMapper, llmService);

        WorkflowDraftGenerationResult result = generator.generate(WorkflowDraftGenerationRequest.builder()
                .agentName("页面助手")
                .requirement("提取参数并触发页面动作")
                .modelInstanceId("model-1")
                .build());

        assertEquals(List.of(), result.validationErrors());
        assertTrue(result.graphSpec().getEdges().stream().anyMatch(edge ->
                "user_input".equals(edge.getFrom())
                        && "extract_params".equals(edge.getTo())
                        && "output".equals(edge.getSourceHandle())
                        && "input".equals(edge.getTargetHandle())));
        Map<String, Object> llmEdge = canvasEdges(result).stream()
                .filter(edge -> "user_input".equals(edge.get("source")) && "extract_params".equals(edge.get("target")))
                .findFirst()
                .orElseThrow();
        assertFalse(llmEdge.containsKey("sourceHandle"));
        assertFalse(llmEdge.containsKey("targetHandle"));
        Map<String, Object> pageActionEdge = canvasEdges(result).stream()
                .filter(edge -> "extract_params".equals(edge.get("source")) && "set_filters".equals(edge.get("target")))
                .findFirst()
                .orElseThrow();
        assertFalse(pageActionEdge.containsKey("sourceHandle"));
        assertFalse(pageActionEdge.containsKey("targetHandle"));
    }

    private WorkflowDraftResource pageActionResource(String actionKey, String title) {
        return pageActionResource(actionKey, title, Map.of());
    }

    private WorkflowDraftResource pageActionResource(String actionKey, String title, Map<String, Object> sampleArgs) {
        return WorkflowDraftResource.builder()
                .kind("PAGE_ACTION")
                .name(actionKey)
                .qualifiedName("teamArchive.list/" + actionKey)
                .projectCode("qmssmp-teams-construction-service")
                .description(title)
                .metadata(Map.of(
                        "pageKey", "teamArchive.list",
                        "routePattern", "/teams/archive",
                        "actionKey", actionKey,
                        "confirmRequired", false,
                        "sampleArgs", sampleArgs,
                        "inputSchema", Map.of(
                                "type", "object",
                                "properties", sampleArgs.keySet().stream()
                                        .collect(java.util.stream.Collectors.toMap(
                                                key -> key,
                                                key -> Map.of("type", "string"))))))
                .build();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> canvasEdges(WorkflowDraftGenerationResult result) {
        return (List<Map<String, Object>>) (List<?>) result.canvasSnapshot().get("edges");
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
