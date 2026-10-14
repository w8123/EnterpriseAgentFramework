package com.enterprise.ai.capability.catalog.retrieval;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CapabilityToolRetrievalControllerTest {

    @Test
    void keepsSearchAndHealthRouteShapeOnCapabilityService() throws Exception {
        assertArrayEquals(new String[] {"/api/tool-retrieval"},
                CapabilityToolRetrievalController.class.getAnnotation(RequestMapping.class).value());

        Method search = CapabilityToolRetrievalController.class.getDeclaredMethod(
                "search", CapabilityToolRetrievalController.SearchRequest.class);
        Method health = CapabilityToolRetrievalController.class.getDeclaredMethod("health");

        assertArrayEquals(new String[] {"/search"}, search.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/health"}, health.getAnnotation(GetMapping.class).value());
    }

    @Test
    void rejectsBlankSearchQuery() {
        CapabilityToolRetrievalController controller = new CapabilityToolRetrievalController(
                mock(CapabilityToolRetrievalService.class));

        ResponseEntity<CapabilityToolRetrievalController.SearchResponse> response =
                controller.search(new CapabilityToolRetrievalController.SearchRequest(
                        "  ", 5, null, null, null, true, null));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("query 不能为空", response.getBody().message());
    }

    @Test
    void searchesToolCandidatesLocally() {
        CapabilityToolRetrievalService service = mock(CapabilityToolRetrievalService.class);
        CapabilityToolRetrievalController controller = new CapabilityToolRetrievalController(service);
        when(service.retrieve("订单", new CapabilityRetrievalScope(List.of(7L), null, null, true), 3, 0.1))
                .thenReturn(List.of(new CapabilityToolCandidate(11L, "order.create", "创建订单", 7L, 2L, 0.92f,
                        "创建订单\norder.create")));

        ResponseEntity<CapabilityToolRetrievalController.SearchResponse> response =
                controller.search(new CapabilityToolRetrievalController.SearchRequest(
                        "订单", 3, List.of(7L), null, null, true, 0.1));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().candidates().size());
        assertEquals("order.create", response.getBody().candidates().get(0).toolName());
        assertEquals("创建订单", response.getBody().candidates().get(0).toolTitle());
    }

    @Test
    void retiredRebuildRoutesAreUnavailable() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new CapabilityToolRetrievalController(
                mock(CapabilityToolRetrievalService.class)))
                .setMessageConverters(new MappingJackson2HttpMessageConverter()).build();

        mvc.perform(post("/api/tool-retrieval/rebuild").contentType("application/json").content("{}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/tool-retrieval/rebuild/status").param("taskId", "task-1"))
                .andExpect(status().isNotFound());
    }

    @Test
    void healthKeepsKeywordReadinessWithoutRebuildState() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new CapabilityToolRetrievalController(
                mock(CapabilityToolRetrievalService.class)))
                .setMessageConverters(new MappingJackson2HttpMessageConverter()).build();

        mvc.perform(get("/api/tool-retrieval/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.ready").value(true))
                .andExpect(jsonPath("$.status").value("KEYWORD_FALLBACK_READY"))
                .andExpect(jsonPath("$.latestRebuild").doesNotExist());
    }
}
