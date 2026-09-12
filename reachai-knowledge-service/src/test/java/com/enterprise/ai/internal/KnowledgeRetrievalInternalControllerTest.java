package com.enterprise.ai.internal;

import com.enterprise.ai.common.dto.ApiResult;
import com.enterprise.ai.common.exception.GlobalExceptionHandler;
import com.enterprise.ai.retrieval.KnowledgeRetrievalCore;
import com.enterprise.ai.retrieval.KnowledgeRetrievalCoreRequest;
import com.enterprise.ai.retrieval.KnowledgeRetrievalCoreResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class KnowledgeRetrievalInternalControllerTest {

    @Test
    void passesSearchModeAndRerankOverrideToKnowledgeRetrievalCore() {
        KnowledgeRetrievalCore core = mock(KnowledgeRetrievalCore.class);
        when(core.retrieve(any())).thenReturn(KnowledgeRetrievalCoreResponse.builder()
                .query("q")
                .searchMode("keyword")
                .items(List.of(KnowledgeRetrievalCoreResponse.RetrievalItem.builder()
                        .chunkId("c1")
                        .fileId("f1")
                        .knowledgeBaseCode("kb1")
                        .fileName("doc.md")
                        .content("hello")
                        .score(0.9f)
                        .build()))
                .totalResults(1)
                .build());
        KnowledgeRetrievalInternalController controller =
                new KnowledgeRetrievalInternalController(core);

        ApiResult<KnowledgeRetrievalInternalController.RetrievalData> result = controller.retrieve(
                new KnowledgeRetrievalInternalController.RetrievalRequest(
                        "q", List.of("kb1"), "u1", 3, 0.2f, "keyword", false));

        ArgumentCaptor<KnowledgeRetrievalCoreRequest> captor = ArgumentCaptor.forClass(KnowledgeRetrievalCoreRequest.class);
        verify(core).retrieve(captor.capture());
        KnowledgeRetrievalCoreRequest request = captor.getValue();
        assertEquals("keyword", request.getSearchMode());
        assertFalse(Boolean.TRUE.equals(request.getRerankEnabled()));
        assertEquals("u1", request.getUserId());
        assertEquals(1, result.getData().hitCount());
        assertEquals("hello", result.getData().hits().get(0).content());
        assertEquals("HIT", result.getData().outcome());
        assertFalse(result.getData().empty());
        assertEquals(1, result.getData().diagnostics().get("returnedHitCount"));
    }

    @Test
    void returnsEmptyWhenUserHasNoAccessibleFiles() {
        KnowledgeRetrievalCore core = mock(KnowledgeRetrievalCore.class);
        KnowledgeRetrievalInternalController controller =
                new KnowledgeRetrievalInternalController(core);

        ApiResult<KnowledgeRetrievalInternalController.RetrievalData> result = controller.retrieve(
                new KnowledgeRetrievalInternalController.RetrievalRequest(
                        "q", List.of("kb1"), "u1", 5, null, "hybrid", true));

        assertEquals(0, result.getData().hitCount());
        assertTrue(result.getData().hits().isEmpty());
        assertEquals("NO_EVIDENCE", result.getData().outcome());
        assertTrue(result.getData().empty());
        assertEquals(0, result.getData().diagnostics().get("returnedContentChars"));
    }

    @Test
    void filtersHitsWithoutResolvedFileIdentity() {
        KnowledgeRetrievalCore core = mock(KnowledgeRetrievalCore.class);
        when(core.retrieve(any())).thenReturn(KnowledgeRetrievalCoreResponse.builder()
                .query("q")
                .items(List.of(
                        KnowledgeRetrievalCoreResponse.RetrievalItem.builder()
                                .chunkId("c1").fileId("f1").content("ok").score(1f).build(),
                        KnowledgeRetrievalCoreResponse.RetrievalItem.builder()
                                .chunkId("c2").fileId(null).content("nope").score(1f).build()))
                .build());
        KnowledgeRetrievalInternalController controller =
                new KnowledgeRetrievalInternalController(core);

        ApiResult<KnowledgeRetrievalInternalController.RetrievalData> result = controller.retrieve(
                new KnowledgeRetrievalInternalController.RetrievalRequest(
                        "q", List.of("kb1"), "u1", 5, null, "vector", true));

        assertEquals(1, result.getData().hitCount());
        assertEquals("ok", result.getData().hits().get(0).content());
    }

    @Test
    void capsEvidenceContentAndReportsOnlyNonSensitiveDiagnostics() {
        KnowledgeRetrievalCore core = mock(KnowledgeRetrievalCore.class);
        String evidence = "sensitive-evidence-" + "x".repeat(5_000);
        List<KnowledgeRetrievalCoreResponse.RetrievalItem> items = IntStream.range(0, 7)
                .mapToObj(index -> KnowledgeRetrievalCoreResponse.RetrievalItem.builder()
                        .chunkId("c" + index)
                        .fileId("f1")
                        .content(evidence)
                        .score(1f)
                        .build())
                .toList();
        when(core.retrieve(any())).thenReturn(KnowledgeRetrievalCoreResponse.builder()
                .query("q")
                .items(items)
                .diagnostics(java.util.Map.of(
                        "mergedCandidateCount", 7,
                        "queryLength", 99,
                        "unsafeMetric", 12))
                .build());
        KnowledgeRetrievalInternalController controller =
                new KnowledgeRetrievalInternalController(core);

        ApiResult<KnowledgeRetrievalInternalController.RetrievalData> result = controller.retrieve(
                new KnowledgeRetrievalInternalController.RetrievalRequest(
                        "q", List.of("kb1"), "u1", 20, null, "hybrid", true));

        assertEquals(6, result.getData().hits().size());
        assertTrue(result.getData().hits().stream().allMatch(hit -> hit.content().length() == 4_000));
        assertEquals(6, result.getData().diagnostics().get("contentTruncatedCount"));
        assertEquals(1, result.getData().diagnostics().get("budgetOmittedHitCount"));
        assertEquals(true, result.getData().diagnostics().get("contentBudgetExhausted"));
        assertEquals(24_000, result.getData().diagnostics().get("returnedContentChars"));
        assertEquals(7, result.getData().diagnostics().get("mergedCandidateCount"));
        assertFalse(result.getData().diagnostics().containsKey("queryLength"));
        assertFalse(result.getData().diagnostics().containsKey("unsafeMetric"));
        assertFalse(result.getData().diagnostics().toString().contains("sensitive-evidence"));
    }

    @Test
    void rejectsBlankUserIdBeforeRetrievalIsCalled() throws Exception {
        KnowledgeRetrievalCore core = mock(KnowledgeRetrievalCore.class);
        org.springframework.validation.beanvalidation.LocalValidatorFactoryBean validator =
                new org.springframework.validation.beanvalidation.LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        MockMvc mockMvc = mockMvc(core, validator);

        mockMvc.perform(post("/internal/knowledge/retrieval/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"query":"refund","knowledgeBaseCodes":["kb1"],"userId":"   "}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
        verifyNoInteractions(core);
    }

    @Test
    void rejectsAbsentUserIdBeforeRetrievalIsCalled() throws Exception {
        KnowledgeRetrievalCore core = mock(KnowledgeRetrievalCore.class);
        org.springframework.validation.beanvalidation.LocalValidatorFactoryBean validator =
                new org.springframework.validation.beanvalidation.LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        MockMvc mockMvc = mockMvc(core, validator);

        mockMvc.perform(post("/internal/knowledge/retrieval/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"query":"refund","knowledgeBaseCodes":["kb1"]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
        verifyNoInteractions(core);
    }

    private static MockMvc mockMvc(KnowledgeRetrievalCore core,
                                   org.springframework.validation.beanvalidation.LocalValidatorFactoryBean validator) {
        return MockMvcBuilders
                .standaloneSetup(new KnowledgeRetrievalInternalController(core))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }
}
