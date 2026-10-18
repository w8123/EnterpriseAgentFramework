package com.enterprise.ai.runtime.internal;

import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.enterprise.ai.runtime.client.control.RuntimeControlCatalogClient;
import com.enterprise.ai.runtime.client.knowledge.RuntimeKnowledgeRetrievalClient;
import com.enterprise.ai.runtime.client.knowledge.RuntimeKnowledgeRetrievalClient.KnowledgeHit;
import com.enterprise.ai.runtime.client.knowledge.RuntimeKnowledgeRetrievalClient.KnowledgeRetrievalData;
import com.enterprise.ai.runtime.client.knowledge.RuntimeKnowledgeRetrievalClient.KnowledgeRetrievalRequest;
import com.enterprise.ai.runtime.client.knowledge.RuntimeKnowledgeRetrievalClient.KnowledgeRetrievalResult;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatData;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatRequest;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatResult;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionResult;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutor;
import com.enterprise.ai.runtime.execution.http.WorkflowHttpClient;
import com.enterprise.ai.runtime.execution.http.WorkflowHttpEgressPolicy;
import com.enterprise.ai.runtime.credential.RuntimeWorkflowCredentialService;
import com.enterprise.ai.runtime.identity.WorkflowExecutionIdentity;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDefinitionEntity;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowReleaseValidationResult;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowReleaseValidationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeKnowledgeQaWorkflowTemplateTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RuntimeKnowledgeRetrievalClient knowledgeClient = mock(RuntimeKnowledgeRetrievalClient.class);
    private final RuntimeModelServiceClient modelClient = mock(RuntimeModelServiceClient.class);
    private final RuntimeGraphSpecExecutor executor = new RuntimeGraphSpecExecutor(
            objectMapper,
            modelClient,
            mock(RuntimeCapabilityCatalogClient.class),
            mock(RuntimeControlCatalogClient.class),
            knowledgeClient,
            new WorkflowHttpClient(objectMapper, WorkflowHttpEgressPolicy.permissiveForTests(),
                    mock(RuntimeWorkflowCredentialService.class)));

    @Test
    void templatePassesReleaseValidation() throws Exception {
        RuntimeWorkflowDefinitionEntity workflow = new RuntimeWorkflowDefinitionEntity();
        workflow.setId("wf-kb");
        workflow.setProjectCode("yudao-erp");
        workflow.setKeySlug(RuntimeKnowledgeQaWorkflowService.keySlug("yudao-erp", "erp_policy"));
        workflow.setName("知识问答");
        workflow.setWorkflowKind("GENERAL");
        workflow.setExecutionEngine("GRAPH_SPEC");
        workflow.setGraphSpecJson(graphJson());

        RuntimeWorkflowReleaseValidationResult result = new RuntimeWorkflowReleaseValidationService(
                mock(RuntimeControlCatalogClient.class), objectMapper).validate(workflow);

        assertTrue(result.valid(), () -> String.valueOf(result.errors()));
        assertEquals("kb-qa-yudao-erp-erp_policy", workflow.getKeySlug());
    }

    @Test
    void evidenceIsAnsweredByTheModelFromTheRetrievedHits() throws Exception {
        when(knowledgeClient.retrieve(any(KnowledgeRetrievalRequest.class))).thenReturn(new KnowledgeRetrievalResult(
                0, "ok", new KnowledgeRetrievalData("退货", List.of(KnowledgeHit.builder()
                        .chunkId("c1").knowledgeBaseCode("erp_policy").title("退货政策")
                        .content("电子产品签收后 7 天内可无理由退货").score(0.9F).build()), 1, "HIT", false, Map.of())));
        when(modelClient.chat(any(ModelChatRequest.class))).thenReturn(new ModelChatResult(
                0, "ok", new ModelChatData("7 天。出处：《ERP 制度》", "m", "p", null, null, null, "stop")));

        RuntimeGraphSpecExecutionResult result = executor.execute(graphJson(),
                Map.of("input", Map.of("question", "电子产品签收后多少天可以无理由退货")),
                WorkflowExecutionIdentity.fromAgent(1L, "yudao-erp", "u1"));

        assertTrue(result.success(), result.code() + " / " + result.answer());
        assertEquals("7 天。出处：《ERP 制度》", result.answer());
        ArgumentCaptor<KnowledgeRetrievalRequest> retrieval = ArgumentCaptor.forClass(KnowledgeRetrievalRequest.class);
        verify(knowledgeClient).retrieve(retrieval.capture());
        assertEquals("电子产品签收后多少天可以无理由退货", retrieval.getValue().getQuery());
        assertEquals(List.of("erp_policy"), retrieval.getValue().getKnowledgeBaseCodes());
        ArgumentCaptor<ModelChatRequest> chat = ArgumentCaptor.forClass(ModelChatRequest.class);
        verify(modelClient).chat(chat.capture());
        String prompt = String.valueOf(chat.getValue().getMessages());
        assertTrue(prompt.contains("电子产品签收后 7 天内可无理由退货"), prompt);
    }

    @Test
    void noEvidenceAnswersWithoutCallingTheModel() throws Exception {
        when(knowledgeClient.retrieve(any(KnowledgeRetrievalRequest.class))).thenReturn(new KnowledgeRetrievalResult(
                0, "ok", new KnowledgeRetrievalData("年假", List.of(), 0, "NO_EVIDENCE", true, Map.of())));

        RuntimeGraphSpecExecutionResult result = executor.execute(graphJson(),
                Map.of("input", Map.of("question", "年假有几天")),
                WorkflowExecutionIdentity.fromAgent(1L, "yudao-erp", "u1"));

        assertTrue(result.success(), result.code() + " / " + result.answer());
        assertEquals("资料《ERP 制度》里没有找到与「年假有几天」相关的内容。", result.answer());
        verify(modelClient, never()).chat(any(ModelChatRequest.class));
    }

    private String graphJson() throws Exception {
        return objectMapper.writeValueAsString(
                RuntimeKnowledgeQaWorkflowService.graphSpec("erp_policy", "ERP 制度", "model-1"));
    }
}
