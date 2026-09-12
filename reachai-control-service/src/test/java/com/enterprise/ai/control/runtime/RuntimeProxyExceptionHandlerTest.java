package com.enterprise.ai.control.runtime;

import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import com.enterprise.ai.common.exception.GlobalExceptionHandler;
import feign.FeignException;
import feign.Request;
import feign.RetryableException;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RuntimeProxyExceptionHandlerTest {

    @Test
    void preservesEmptyNotFoundAfterAgentDeletion() throws Exception {
        RuntimeProxyClient runtimeClient = mock(RuntimeProxyClient.class);
        when(runtimeClient.getAgent("deleted-agent")).thenThrow(new FeignException.NotFound(
                "upstream-private-address", Request.create(Request.HttpMethod.GET,
                "/api/agents/deleted-agent", Map.of(), null, StandardCharsets.UTF_8, null),
                new byte[0], Map.of()));

        mockMvc(runtimeClient).perform(get("/api/agents/deleted-agent"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));
    }

    @Test
    void preservesStructuredNotFoundForRepeatedAgentDeletion() throws Exception {
        RuntimeProxyClient runtimeClient = mock(RuntimeProxyClient.class);
        byte[] body = "{\"code\":\"AGENT_NOT_FOUND\"}".getBytes(StandardCharsets.UTF_8);
        when(runtimeClient.deleteAgent("deleted-agent")).thenThrow(new FeignException.NotFound(
                "upstream-private-address", Request.create(Request.HttpMethod.DELETE,
                "/api/agents/deleted-agent", Map.of(), null, StandardCharsets.UTF_8, null), body,
                Map.of(HttpHeaders.CONTENT_TYPE, List.of(MediaType.APPLICATION_JSON_VALUE),
                        HttpHeaders.CONNECTION, List.of("close"), HttpHeaders.CONTENT_LENGTH, List.of("999"))));

        mockMvc(runtimeClient).perform(delete("/api/agents/deleted-agent"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("AGENT_NOT_FOUND"))
                .andExpect(header().doesNotExist(HttpHeaders.CONNECTION));
    }

    @Test
    void preservesRuntimeConflictForWorkflowWorkingCopySave() throws Exception {
        RuntimeProxyClient runtimeClient = mock(RuntimeProxyClient.class);
        Map<String, Object> request = Map.of("baseRevision", "revision-1");
        when(runtimeClient.saveWorkflowWorkingCopy("wf-1", request))
                .thenThrow(conflict(Request.HttpMethod.PUT, "/api/workflows/wf-1/working-copy"));

        mockMvc(runtimeClient)
                .perform(put("/api/workflows/wf-1/working-copy")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseRevision\":\"revision-1\"}"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string(HttpHeaders.ETAG, "\"revision-2\""))
                .andExpect(jsonPath("$.code").value("WORKFLOW_WORKING_COPY_CONFLICT"))
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
                .andExpect(jsonPath("$.code").value("WORKFLOW_WORKING_COPY_CONFLICT"))
                .andExpect(jsonPath("$.currentRevision").value("revision-2"));
    }

    @Test
    void reportsWorkflowAuthoringTimeoutAsActionableGatewayTimeout() throws Exception {
        RuntimeProxyClient runtimeClient = mock(RuntimeProxyClient.class);
        Map<String, Object> request = Map.of("instruction", "narrow edit");
        when(runtimeClient.editWorkflowProposal(request))
                .thenThrow(new RetryableException(
                        500,
                        "Read timed out executing POST http://runtime/api/workflows/studio/proposals/edit",
                        Request.HttpMethod.POST,
                        (Long) null,
                        Request.create(
                                Request.HttpMethod.POST,
                                "/api/workflows/studio/proposals/edit",
                                Map.of(),
                                null,
                                StandardCharsets.UTF_8,
                                null)));

        mockMvc(runtimeClient)
                .perform(post("/api/workflows/studio/proposals/edit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"instruction\":\"narrow edit\"}"))
                .andExpect(status().isGatewayTimeout())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(504))
                .andExpect(jsonPath("$.message").value(
                        "Workflow AI 编排超时，当前工作副本未变更。请缩小修改范围后重新生成；如持续失败，请检查 Runtime 与模型服务。"));
    }

    private static MockMvc mockMvc(RuntimeProxyClient runtimeClient) {
        return MockMvcBuilders
                .standaloneSetup(new ControlRuntimePublicController(runtimeClient))
                .setControllerAdvice(new GlobalExceptionHandler(), new RuntimeProxyExceptionHandler())
                .build();
    }

    private static FeignException.Conflict conflict(Request.HttpMethod method, String path) {
        byte[] body = ("{\"code\":\"WORKFLOW_WORKING_COPY_CONFLICT\","
                + "\"message\":\"Workflow working copy changed after it was loaded\","
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
