package com.enterprise.ai.control.runtime;

import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import feign.FeignException;
import feign.Request;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RuntimeProxyExceptionHandlerTest {

    @Test
    void preservesRuntimeConflictForWorkflowStudioSave() throws Exception {
        RuntimeProxyClient runtimeClient = mock(RuntimeProxyClient.class);
        Map<String, Object> request = Map.of("baseRevision", "revision-1");
        when(runtimeClient.saveWorkflowStudio("wf-1", request))
                .thenThrow(conflict(Request.HttpMethod.PUT, "/api/workflows/wf-1/studio"));

        mockMvc(runtimeClient)
                .perform(put("/api/workflows/wf-1/studio")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseRevision\":\"revision-1\"}"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string(HttpHeaders.ETAG, "\"revision-2\""))
                .andExpect(jsonPath("$.code").value("WORKFLOW_DRAFT_CONFLICT"))
                .andExpect(jsonPath("$.currentRevision").value("revision-2"));
    }

    @Test
    void preservesRuntimeConflictForWorkflowAiCodingPatch() throws Exception {
        RuntimeProxyClient runtimeClient = mock(RuntimeProxyClient.class);
        Map<String, Object> request = Map.of("baseRevision", "revision-1", "operations", List.of());
        when(runtimeClient.patchWorkflowAiCoding("wf-1", request))
                .thenThrow(conflict(Request.HttpMethod.POST, "/api/workflows/wf-1/ai-coding/patch"));

        mockMvc(runtimeClient)
                .perform(post("/api/workflows/wf-1/ai-coding/patch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseRevision\":\"revision-1\",\"operations\":[]}"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string(HttpHeaders.ETAG, "\"revision-2\""))
                .andExpect(jsonPath("$.code").value("WORKFLOW_DRAFT_CONFLICT"))
                .andExpect(jsonPath("$.currentRevision").value("revision-2"));
    }

    private static MockMvc mockMvc(RuntimeProxyClient runtimeClient) {
        return MockMvcBuilders
                .standaloneSetup(new ControlRuntimePublicController(runtimeClient))
                .setControllerAdvice(new RuntimeProxyExceptionHandler())
                .build();
    }

    private static FeignException.Conflict conflict(Request.HttpMethod method, String path) {
        byte[] body = ("{\"code\":\"WORKFLOW_DRAFT_CONFLICT\","
                + "\"message\":\"Workflow draft changed after it was loaded\","
                + "\"currentRevision\":\"revision-2\"}").getBytes(StandardCharsets.UTF_8);
        return new FeignException.Conflict(
                "conflict",
                Request.create(method, path, Map.of(), null, StandardCharsets.UTF_8, null),
                body,
                Map.of(
                        "Content-Type", List.of(MediaType.APPLICATION_JSON_VALUE),
                        HttpHeaders.ETAG, List.of("\"revision-2\""),
                        HttpHeaders.CONNECTION, List.of("close"),
                        HttpHeaders.CONTENT_LENGTH, List.of(Integer.toString(body.length))));
    }
}
