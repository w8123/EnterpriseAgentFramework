package com.enterprise.ai.agent.platform.control.context.memory;

import com.enterprise.ai.agent.platform.control.context.ContextItemResponse;
import com.enterprise.ai.agent.platform.control.context.ContextQueryRequest;
import com.enterprise.ai.agent.platform.control.context.ContextRetrievalService;
import com.enterprise.ai.agent.platform.control.context.ContextSearchResult;
import com.enterprise.ai.agent.platform.control.identity.EmbedSessionEntity;
import com.enterprise.ai.agent.platform.control.identity.EmbedTokenClaims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RuntimeMemoryCandidateServiceTest {

    private ContextMemoryCandidateService candidateService;
    private RuntimeMemoryExtractor memoryExtractor;
    private ContextRetrievalService retrievalService;
    private RuntimeMemoryCandidateService service;

    @BeforeEach
    void setUp() {
        candidateService = mock(ContextMemoryCandidateService.class);
        memoryExtractor = mock(RuntimeMemoryExtractor.class);
        retrievalService = mock(ContextRetrievalService.class);
        service = new RuntimeMemoryCandidateService(candidateService, memoryExtractor, retrievalService);
    }

    @Test
    void llmExtractionCreatesStructuredCandidatesWhenModelAvailable() {
        when(candidateService.createRuntimeUserCandidateFromInteraction(any()))
                .thenReturn(ContextMemoryCandidateResponse.builder().id(51L).build(),
                        ContextMemoryCandidateResponse.builder().id(52L).build());
        when(candidateService.resolveUserId(any(), any(), any())).thenReturn("user-a");
        when(memoryExtractor.extract(any())).thenReturn(List.of(
                RuntimeMemoryExtraction.builder()
                        .candidateType(ContextMemoryCandidateType.PREFERENCE)
                        .title("界面偏好")
                        .content("用户偏好使用深色模式")
                        .summary("偏好深色模式")
                        .reason("用户明确表达了长期界面偏好")
                        .confidence(new BigDecimal("0.9100"))
                        .build(),
                RuntimeMemoryExtraction.builder()
                        .candidateType(ContextMemoryCandidateType.FACT)
                        .title("所在部门")
                        .content("用户属于财务共享中心")
                        .summary("财务共享中心用户")
                        .reason("用户陈述了可长期复用的身份事实")
                        .confidence(new BigDecimal("0.8400"))
                        .build()));

        Map<String, Object> metadata = metadata("user-a");
        metadata.put("modelInstanceId", "model-memory");
        RuntimeMemoryCandidateResult result = service.tryCreateFromInteraction(
                session("user-a"), claims("user-a"), metadata,
                "我在财务共享中心，以后界面请默认深色模式",
                "好的，我会记住你的偏好。",
                "trace-llm",
                RuntimeMemoryCandidateIdentity.empty());

        assertTrue(result.isCreated());
        assertEquals(2, result.getCreatedCount());
        assertEquals(List.of(51L, 52L), result.getCandidateIds());
        assertEquals(51L, result.getCandidateId());

        var captor = org.mockito.ArgumentCaptor.forClass(ContextMemoryCandidateCreateRequest.class);
        verify(candidateService, times(2)).createRuntimeUserCandidateFromInteraction(captor.capture());
        assertEquals("用户偏好使用深色模式", captor.getAllValues().get(0).getContent());
        assertEquals(ContextMemoryCandidateType.PREFERENCE.name(), captor.getAllValues().get(0).getCandidateType());
        assertEquals(new BigDecimal("0.9100"), captor.getAllValues().get(0).getConfidence());
        assertEquals("用户属于财务共享中心", captor.getAllValues().get(1).getContent());
        assertEquals(ContextMemoryCandidateType.FACT.name(), captor.getAllValues().get(1).getCandidateType());
        assertTrue(captor.getAllValues().get(0).getMetadataJson().contains("\"extractionMode\":\"LLM\""));
        assertTrue(captor.getAllValues().get(0).getMetadataJson().contains("\"modelInstanceId\":\"model-memory\""));
    }

    @Test
    void llmExtractionEmptySkipsWithoutKeywordFallback() {
        when(candidateService.resolveUserId(any(), any(), any())).thenReturn("user-a");
        when(memoryExtractor.extract(any())).thenReturn(List.of());

        Map<String, Object> metadata = metadata("user-a");
        metadata.put("modelInstanceId", "model-memory");
        RuntimeMemoryCandidateResult result = service.tryCreateFromInteraction(
                session("user-a"), claims("user-a"), metadata,
                "今天帮我查一下订单状态",
                "订单状态是处理中。",
                "trace-empty",
                RuntimeMemoryCandidateIdentity.empty());

        assertFalse(result.isCreated());
        assertEquals("llm-no-candidates", result.getSkippedReason());
        verify(candidateService, never()).createRuntimeUserCandidateFromInteraction(any());
    }

    @Test
    void llmExtractionSkipsCandidateAlreadyRememberedAsActiveItem() {
        when(candidateService.resolveUserId(any(), any(), any())).thenReturn("user-a");
        when(memoryExtractor.extract(any())).thenReturn(List.of(
                RuntimeMemoryExtraction.builder()
                        .candidateType(ContextMemoryCandidateType.PREFERENCE)
                        .content("用户偏好使用深色模式")
                        .confidence(new BigDecimal("0.9000"))
                        .build()));
        when(retrievalService.search(any())).thenReturn(List.of(ContextSearchResult.builder()
                .item(ContextItemResponse.builder()
                        .id(800L)
                        .itemType("PREFERENCE")
                        .content("用户偏好使用深色模式")
                        .build())
                .build()));

        Map<String, Object> metadata = metadata("user-a");
        metadata.put("modelInstanceId", "model-memory");
        RuntimeMemoryCandidateResult result = service.tryCreateFromInteraction(
                session("user-a"), claims("user-a"), metadata,
                "以后界面请默认深色模式",
                "好的。",
                "trace-known",
                RuntimeMemoryCandidateIdentity.empty());

        assertFalse(result.isCreated());
        assertEquals("already-remembered", result.getSkippedReason());
        verify(candidateService, never()).createRuntimeUserCandidateFromInteraction(any());
    }

    @Test
    void llmExtractionAddsRelatedActiveMemoryHintsToCandidateMetadata() {
        when(candidateService.resolveUserId(any(), any(), any())).thenReturn("user-a");
        when(memoryExtractor.extract(any())).thenReturn(List.of(
                RuntimeMemoryExtraction.builder()
                        .candidateType(ContextMemoryCandidateType.PREFERENCE)
                        .title("界面主题")
                        .content("我喜欢深色主题")
                        .confidence(new BigDecimal("0.8800"))
                        .build()));
        when(retrievalService.search(any())).thenReturn(List.of(ContextSearchResult.builder()
                .item(ContextItemResponse.builder()
                        .id(801L)
                        .itemType("PREFERENCE")
                        .title("界面偏好")
                        .content("用户偏好使用深色模式")
                        .build())
                .rankScore(8.25D)
                .hitReason("token keyword match")
                .scoreBreakdown("tokens=深色,主题")
                .build()));
        when(candidateService.createRuntimeUserCandidateFromInteraction(any()))
                .thenReturn(ContextMemoryCandidateResponse.builder().id(62L).build());

        Map<String, Object> metadata = metadata("user-a");
        metadata.put("modelInstanceId", "model-memory");
        RuntimeMemoryCandidateResult result = service.tryCreateFromInteraction(
                session("user-a"), claims("user-a"), metadata,
                "以后界面请默认深色主题",
                "好的。",
                "trace-related",
                RuntimeMemoryCandidateIdentity.empty());

        assertTrue(result.isCreated());
        var requestCaptor = org.mockito.ArgumentCaptor.forClass(ContextMemoryCandidateCreateRequest.class);
        verify(candidateService).createRuntimeUserCandidateFromInteraction(requestCaptor.capture());
        String candidateMetadata = requestCaptor.getValue().getMetadataJson();
        assertTrue(candidateMetadata.contains("\"qualitySignals\""));
        assertTrue(candidateMetadata.contains("\"relatedActiveItems\""));
        assertTrue(candidateMetadata.contains("\"itemId\":801"));
        assertTrue(candidateMetadata.contains("\"reviewHint\":\"related-active-memory\""));
        assertFalse(candidateMetadata.contains("用户偏好使用深色模式"));

        var queryCaptor = org.mockito.ArgumentCaptor.forClass(ContextQueryRequest.class);
        verify(retrievalService).search(queryCaptor.capture());
        assertEquals("HYBRID", queryCaptor.getValue().getRetrievalMode());
    }

    @Test
    void llmFailureFallsBackToExplicitRuleCandidate() {
        when(candidateService.createRuntimeUserCandidateFromInteraction(any()))
                .thenReturn(ContextMemoryCandidateResponse.builder().id(61L).build());
        when(candidateService.resolveUserId(any(), any(), any())).thenReturn("user-a");
        when(memoryExtractor.extract(any())).thenThrow(new IllegalStateException("model offline"));

        Map<String, Object> metadata = metadata("user-a");
        metadata.put("modelInstanceId", "model-memory");
        RuntimeMemoryCandidateResult result = service.tryCreateFromInteraction(
                session("user-a"), claims("user-a"), metadata,
                "请记住我喜欢深色模式",
                "好的。",
                "trace-fallback",
                RuntimeMemoryCandidateIdentity.empty());

        assertTrue(result.isCreated());
        assertEquals("RULE_FALLBACK", result.getExtractionMode());
        assertEquals(61L, result.getCandidateId());
    }

    @Test
    void agentInteractionCandidateIncludesAgentIdAndAgentKey() {
        when(candidateService.createRuntimeUserCandidateFromInteraction(any()))
                .thenReturn(ContextMemoryCandidateResponse.builder().id(42L).build());
        when(candidateService.resolveUserId(any(), any(), any())).thenReturn("user-a");

        RuntimeMemoryCandidateIdentity identity = new RuntimeMemoryCandidateIdentity(
                "agent-1", "global-agent", null, null);
        RuntimeMemoryCandidateResult result = service.tryCreateFromInteraction(
                session("user-a"), claims("user-a"), metadata("user-a"),
                "请记住我喜欢深色模式", "trace-1", identity);

        assertTrue(result.isCreated());
        var captor = org.mockito.ArgumentCaptor.forClass(ContextMemoryCandidateCreateRequest.class);
        verify(candidateService).createRuntimeUserCandidateFromInteraction(captor.capture());
        assertEquals("agent-1", captor.getValue().getAgentId());
        assertEquals("global-agent", captor.getValue().getAgentKey());
        assertNull(captor.getValue().getWorkflowId());
    }

    @Test
    void workflowInteractionCandidateIncludesWorkflowIdentity() {
        when(candidateService.createRuntimeUserCandidateFromInteraction(any()))
                .thenReturn(ContextMemoryCandidateResponse.builder().id(43L).build());
        when(candidateService.resolveUserId(any(), any(), any())).thenReturn("user-a");

        Map<String, Object> workflowMetadata = metadata("user-a");
        workflowMetadata.put("bindingId", 7L);
        workflowMetadata.put("workflowId", "wf-1");
        workflowMetadata.put("workflowKey", "orders");

        RuntimeMemoryCandidateIdentity identity = new RuntimeMemoryCandidateIdentity(
                "agent-1", "global-agent", "wf-1", "orders");
        RuntimeMemoryCandidateResult result = service.tryCreateFromInteraction(
                session("user-a"), claims("user-a"), workflowMetadata,
                "请记住以后都用列表视图", "trace-2", identity);

        assertTrue(result.isCreated());
        var captor = org.mockito.ArgumentCaptor.forClass(ContextMemoryCandidateCreateRequest.class);
        verify(candidateService).createRuntimeUserCandidateFromInteraction(captor.capture());
        assertEquals("agent-1", captor.getValue().getAgentId());
        assertEquals("global-agent", captor.getValue().getAgentKey());
        assertEquals("wf-1", captor.getValue().getWorkflowId());
        assertEquals("orders", captor.getValue().getWorkflowKey());
    }

    @Test
    void explicitMemoryMessageCreatesPendingCandidate() {
        when(candidateService.createRuntimeUserCandidateFromInteraction(any()))
                .thenReturn(ContextMemoryCandidateResponse.builder().id(42L).build());
        when(candidateService.resolveUserId(any(), any(), any())).thenReturn("user-a");

        RuntimeMemoryCandidateResult result = service.tryCreateFromInteraction(
                session("user-a"), claims("user-a"), metadata("user-a"), "请记住我喜欢深色模式", "trace-1");

        assertTrue(result.isCreated());
        assertEquals(1, result.getCreatedCount());
        assertEquals(42L, result.getCandidateId());

        var captor = org.mockito.ArgumentCaptor.forClass(ContextMemoryCandidateCreateRequest.class);
        verify(candidateService).createRuntimeUserCandidateFromInteraction(captor.capture());
        assertEquals("请记住我喜欢深色模式", captor.getValue().getContent());
        assertEquals("user-a", captor.getValue().getUserId());
    }

    @Test
    void normalMessageDoesNotCreateCandidate() {
        RuntimeMemoryCandidateResult result = service.tryCreateFromInteraction(
                session("user-a"), claims("user-a"), metadata("user-a"), "今天天气怎么样", "trace-2");

        assertFalse(result.isCreated());
        assertEquals("no-memory-intent", result.getSkippedReason());
        verify(candidateService, never()).createRuntimeUserCandidateFromInteraction(any());
    }

    @Test
    void candidateCreationFailureReturnsSkippedReason() {
        when(candidateService.resolveUserId(any(), any(), any())).thenReturn("user-a");
        when(candidateService.createRuntimeUserCandidateFromInteraction(any()))
                .thenThrow(new IllegalArgumentException("db down"));

        RuntimeMemoryCandidateResult result = service.tryCreateFromInteraction(
                session("user-a"), claims("user-a"), metadata("user-a"), "请记住这个", "trace-3");

        assertFalse(result.isCreated());
        assertTrue(result.getSkippedReason().startsWith("candidate-error:"));
    }

    @Test
    void hasExplicitMemoryIntentMatchesTriggerWords() {
        assertTrue(service.hasExplicitMemoryIntent("请记住我喜欢深色模式"));
        assertTrue(service.hasExplicitMemoryIntent("我的偏好是简洁界面"));
        assertFalse(service.hasExplicitMemoryIntent("今天天气怎么样"));
    }

    private EmbedSessionEntity session(String userId) {
        EmbedSessionEntity session = new EmbedSessionEntity();
        session.setSessionId("sess-1");
        session.setTenantId("default");
        session.setProjectCode("demo-project");
        session.setGlobalUserId(userId);
        session.setExternalUserId(userId);
        return session;
    }

    private EmbedTokenClaims claims(String userId) {
        EmbedTokenClaims claims = new EmbedTokenClaims();
        claims.setGlobalUserId(userId);
        claims.setExternalUserId(userId);
        return claims;
    }

    private Map<String, Object> metadata(String userId) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("tenantId", "default");
        metadata.put("projectCode", "demo-project");
        metadata.put("globalUserId", userId);
        metadata.put("externalUserId", userId);
        return metadata;
    }
}
