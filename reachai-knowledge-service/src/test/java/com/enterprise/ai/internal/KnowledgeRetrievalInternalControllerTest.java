package com.enterprise.ai.internal;

import com.enterprise.ai.common.dto.ApiResult;
import com.enterprise.ai.common.exception.GlobalExceptionHandler;
import com.enterprise.ai.retrieval.KnowledgeRetrievalCore;
import com.enterprise.ai.retrieval.KnowledgeRetrievalCoreRequest;
import com.enterprise.ai.retrieval.KnowledgeRetrievalCoreResponse;
import com.enterprise.ai.security.PermissionService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

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
        PermissionService permissionService = mock(PermissionService.class);
        when(permissionService.getAccessibleFileIds("u1")).thenReturn(List.of("f1"));
        when(permissionService.buildMilvusFilter(List.of("f1"))).thenReturn("file_id in [\"f1\"]");
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
                new KnowledgeRetrievalInternalController(core, permissionService);

        ApiResult<KnowledgeRetrievalInternalController.RetrievalData> result = controller.retrieve(
                new KnowledgeRetrievalInternalController.RetrievalRequest(
                        "q", List.of("kb1"), "u1", 3, 0.2f, "keyword", false));

        ArgumentCaptor<KnowledgeRetrievalCoreRequest> captor = ArgumentCaptor.forClass(KnowledgeRetrievalCoreRequest.class);
        verify(core).retrieve(captor.capture());
        KnowledgeRetrievalCoreRequest request = captor.getValue();
        assertEquals("keyword", request.getSearchMode());
        assertFalse(Boolean.TRUE.equals(request.getRerankEnabled()));
        assertEquals(List.of("f1"), request.getAccessibleFileIds());
        assertEquals("file_id in [\"f1\"]", request.getFileIdFilterExpression());
        assertEquals(1, result.getData().hitCount());
        assertEquals("hello", result.getData().hits().get(0).content());
    }

    @Test
    void returnsEmptyWhenUserHasNoAccessibleFiles() {
        KnowledgeRetrievalCore core = mock(KnowledgeRetrievalCore.class);
        PermissionService permissionService = mock(PermissionService.class);
        when(permissionService.getAccessibleFileIds("u1")).thenReturn(List.of());
        KnowledgeRetrievalInternalController controller =
                new KnowledgeRetrievalInternalController(core, permissionService);

        ApiResult<KnowledgeRetrievalInternalController.RetrievalData> result = controller.retrieve(
                new KnowledgeRetrievalInternalController.RetrievalRequest(
                        "q", List.of("kb1"), "u1", 5, null, "hybrid", true));

        assertEquals(0, result.getData().hitCount());
        assertTrue(result.getData().hits().isEmpty());
    }

    @Test
    void filtersUnauthorizedFileHits() {
        KnowledgeRetrievalCore core = mock(KnowledgeRetrievalCore.class);
        PermissionService permissionService = mock(PermissionService.class);
        when(permissionService.getAccessibleFileIds("u1")).thenReturn(List.of("f1"));
        when(permissionService.buildMilvusFilter(List.of("f1"))).thenReturn("file_id in [\"f1\"]");
        when(core.retrieve(any())).thenReturn(KnowledgeRetrievalCoreResponse.builder()
                .query("q")
                .items(List.of(
                        KnowledgeRetrievalCoreResponse.RetrievalItem.builder()
                                .chunkId("c1").fileId("f1").content("ok").score(1f).build(),
                        KnowledgeRetrievalCoreResponse.RetrievalItem.builder()
                                .chunkId("c2").fileId("secret").content("nope").score(1f).build()))
                .build());
        KnowledgeRetrievalInternalController controller =
                new KnowledgeRetrievalInternalController(core, permissionService);

        ApiResult<KnowledgeRetrievalInternalController.RetrievalData> result = controller.retrieve(
                new KnowledgeRetrievalInternalController.RetrievalRequest(
                        "q", List.of("kb1"), "u1", 5, null, "vector", true));

        assertEquals(1, result.getData().hitCount());
        assertEquals("ok", result.getData().hits().get(0).content());
    }

    @Test
    void rejectsMissingUserIdBeforePermissionServiceIsCalled() throws Exception {
        KnowledgeRetrievalCore core = mock(KnowledgeRetrievalCore.class);
        PermissionService permissionService = mock(PermissionService.class);
        org.springframework.validation.beanvalidation.LocalValidatorFactoryBean validator =
                new org.springframework.validation.beanvalidation.LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        MockMvc mockMvc = mockMvc(core, permissionService, validator);

        mockMvc.perform(post("/internal/knowledge/retrieval/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"query":"refund","knowledgeBaseCodes":["kb1"],"userId":"   "}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        verifyNoInteractions(permissionService, core);
    }

    @Test
    void rejectsAbsentUserIdFieldBeforePermissionServiceIsCalled() throws Exception {
        KnowledgeRetrievalCore core = mock(KnowledgeRetrievalCore.class);
        PermissionService permissionService = mock(PermissionService.class);
        org.springframework.validation.beanvalidation.LocalValidatorFactoryBean validator =
                new org.springframework.validation.beanvalidation.LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        MockMvc mockMvc = mockMvc(core, permissionService, validator);

        mockMvc.perform(post("/internal/knowledge/retrieval/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"query":"refund","knowledgeBaseCodes":["kb1"]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        verifyNoInteractions(permissionService, core);
    }

    private static MockMvc mockMvc(KnowledgeRetrievalCore core,
                                   PermissionService permissionService,
                                   org.springframework.validation.beanvalidation.LocalValidatorFactoryBean validator) {
        return MockMvcBuilders
                .standaloneSetup(new KnowledgeRetrievalInternalController(core, permissionService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }
}
