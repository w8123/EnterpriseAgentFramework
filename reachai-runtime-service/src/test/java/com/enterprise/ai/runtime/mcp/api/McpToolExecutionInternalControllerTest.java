package com.enterprise.ai.runtime.mcp.api;

import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.common.internalauth.InternalServiceHmac;
import com.enterprise.ai.runtime.execution.identity.WorkflowExecutionIdentity;
import com.enterprise.ai.runtime.internalauth.InMemoryInternalAuthNonceStore;
import com.enterprise.ai.runtime.internalauth.InternalServiceAuthFilter;
import com.enterprise.ai.runtime.internalauth.InternalServiceAuthProperties;
import com.enterprise.ai.runtime.internalauth.InternalServiceAuthVerifier;
import com.enterprise.ai.runtime.mcp.application.RuntimeMcpToolExecutionService;
import com.enterprise.ai.runtime.mcp.application.RuntimeMcpToolExecutionService.ExecutionOutcome;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class McpToolExecutionInternalControllerTest {

    private static final String PATH = "/internal/runtime/mcp/tool-executions";
    private static final String SECRET = "mcp-runtime-internal-test-secret";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private RuntimeMcpToolExecutionService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(RuntimeMcpToolExecutionService.class);
        McpToolExecutionInternalController controller = new McpToolExecutionInternalController(service);
        InternalServiceAuthProperties properties = new InternalServiceAuthProperties(
                SECRET, 300, 600, 1000, 1_048_576);
        InternalServiceAuthFilter filter = new InternalServiceAuthFilter(
                new InternalServiceAuthVerifier(properties, new InMemoryInternalAuthNonceStore()), properties);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).addFilters(filter).build();
    }

    @Test
    void rejectsUnsignedTamperedAndWrongIdentityRequestsBeforeDispatch() throws Exception {
        byte[] original = body(request("orders.lookup"));
        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(original))
                .andExpect(status().isUnauthorized());

        byte[] tampered = body(request("orders.delete"));
        mockMvc.perform(signed(original, tampered,
                        InternalServiceAuthHeaders.IDENTITY_SOURCE_MCP_REMOTE_CLIENT))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(signed(original, original,
                        InternalServiceAuthHeaders.IDENTITY_SOURCE_PLATFORM_SESSION))
                .andExpect(status().isUnauthorized());
        verify(service, never()).execute(any(), any());
    }

    @Test
    void rejectsSignedRequestsWithoutProjectScopeOrWithTenantMismatch() throws Exception {
        Map<String, Object> missingProject = new LinkedHashMap<>(request("orders.lookup"));
        missingProject.put("metadata", Map.of("tenantId", "tenant-a", "mcpClientId", 17));
        byte[] missing = body(missingProject);
        mockMvc.perform(signed(missing, missing,
                        InternalServiceAuthHeaders.IDENTITY_SOURCE_MCP_REMOTE_CLIENT))
                .andExpect(status().isUnauthorized());

        Map<String, Object> mismatch = new LinkedHashMap<>(request("orders.lookup"));
        mismatch.put("metadata", Map.of(
                "tenantId", "tenant-b", "mcpClientId", 17,
                "projectId", 8, "projectCode", "orders"));
        byte[] mismatched = body(mismatch);
        mockMvc.perform(signed(mismatched, mismatched,
                        InternalServiceAuthHeaders.IDENTITY_SOURCE_MCP_REMOTE_CLIENT))
                .andExpect(status().isUnauthorized());

        Map<String, Object> wrongPrincipal = new LinkedHashMap<>(request("orders.lookup"));
        wrongPrincipal.put("metadata", Map.of(
                "tenantId", "tenant-a", "mcpClientId", 18,
                "projectId", 8, "projectCode", "orders"));
        byte[] wrongPrincipalBody = body(wrongPrincipal);
        mockMvc.perform(signed(wrongPrincipalBody, wrongPrincipalBody,
                        InternalServiceAuthHeaders.IDENTITY_SOURCE_MCP_REMOTE_CLIENT))
                .andExpect(status().isUnauthorized());
        verify(service, never()).execute(any(), any());
    }

    @Test
    void dispatchesOnlyTheSignedTenantAndProjectIdentityAndReturnsDurableIds() throws Exception {
        when(service.execute(any(), any())).thenReturn(new ExecutionOutcome(
                true, "OK", Map.of("data", Map.of("orderId", 42)), "77", "mcp_trace", null));
        byte[] payload = body(request("orders.lookup"));

        mockMvc.perform(signed(payload, payload,
                        InternalServiceAuthHeaders.IDENTITY_SOURCE_MCP_REMOTE_CLIENT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.runId").value("77"))
                .andExpect(jsonPath("$.traceId").value("mcp_trace"));

        ArgumentCaptor<WorkflowExecutionIdentity> identity =
                ArgumentCaptor.forClass(WorkflowExecutionIdentity.class);
        verify(service).execute(any(), identity.capture());
        assertEquals(WorkflowExecutionIdentity.Source.MCP_REMOTE_CLIENT, identity.getValue().source());
        assertEquals("tenant-a", identity.getValue().tenantId());
        assertEquals(8L, identity.getValue().projectId());
        assertEquals("orders", identity.getValue().projectCode());
        assertEquals("mcp-client-17", identity.getValue().userId());
        assertFalse(identity.getValue().userTrusted());
    }

    private Map<String, Object> request(String sourceRef) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("sourceKind", "CAPABILITY");
        request.put("sourceRef", sourceRef);
        request.put("toolName", "lookup");
        request.put("arguments", Map.of("id", 42));
        request.put("metadata", Map.of(
                "tenantId", "tenant-a", "mcpClientId", 17,
                "projectId", 8, "projectCode", "orders"));
        request.put("timeoutMs", 5_000);
        return request;
    }

    private byte[] body(Map<String, Object> values) throws Exception {
        return objectMapper.writeValueAsBytes(values);
    }

    private MockHttpServletRequestBuilder signed(
            byte[] signedBody, byte[] sentBody, String identitySource) {
        String tenantId = "tenant-a";
        String userId = "mcp-client-17";
        String timestamp = String.valueOf(System.currentTimeMillis());
        String nonce = UUID.randomUUID().toString();
        String digest = InternalServiceHmac.bodySha256Hex(signedBody);
        String canonical = InternalServiceHmac.canonical(
                "POST", PATH, InternalServiceAuthHeaders.CALLER_CONTROL,
                identitySource, tenantId, userId, timestamp, nonce, digest);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(InternalServiceAuthHeaders.CALLER, InternalServiceAuthHeaders.CALLER_CONTROL);
        headers.put(InternalServiceAuthHeaders.IDENTITY_SOURCE, identitySource);
        headers.put(InternalServiceAuthHeaders.IDENTITY_TENANT_ID, tenantId);
        headers.put(InternalServiceAuthHeaders.IDENTITY_USER_ID, userId);
        headers.put(InternalServiceAuthHeaders.TIMESTAMP, timestamp);
        headers.put(InternalServiceAuthHeaders.NONCE, nonce);
        headers.put(InternalServiceAuthHeaders.BODY_SHA256, digest);
        headers.put(InternalServiceAuthHeaders.SIGNATURE, InternalServiceHmac.sign(SECRET, canonical));
        MockHttpServletRequestBuilder builder = post(PATH)
                .contentType(MediaType.APPLICATION_JSON).content(sentBody);
        headers.forEach(builder::header);
        return builder;
    }
}
