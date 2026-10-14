package com.enterprise.ai.runtime.runops.consolehttpapi;

import com.enterprise.ai.common.capability.HttpApiConsoleContracts;
import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.enterprise.ai.runtime.credential.RuntimeWorkflowCredentialService;
import com.enterprise.ai.runtime.execution.http.WorkflowHttpEgressPolicy;
import com.enterprise.ai.runtime.internalauth.VerifiedInternalServiceAuth;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RuntimeHttpApiConnectionEgressContractTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void deniedLoopbackIsAnActionableConflictAndCannotWriteConnection() throws Exception {
        rejects("http://127.0.0.1:8089", WorkflowHttpEgressPolicy.ERROR_DNS);
    }

    @Test void metadataHostCannotBeMadeUsableByConnectionSave() throws Exception {
        rejects("http://metadata.google.internal", WorkflowHttpEgressPolicy.ERROR_HOST);
    }

    private void rejects(String origin, String errorCode) throws Exception {
        var mapper = mock(RuntimeHttpApiConnectionMapper.class);
        var capability = mock(RuntimeCapabilityCatalogClient.class);
        var credentials = mock(RuntimeWorkflowCredentialService.class);
        var owner = new HttpApiConsoleContracts.ExecutionContext(1, 3L, "http-api:bzjs21:count",
                36L, "bzjs21", "dev", "GET", "/count", true, "ACCEPTED", null,
                "same-hash", "same-hash", "source", json.createObjectNode(), true, null);
        when(capability.getHttpApiExecutionContext(3L, "bzjs21")).thenReturn(owner);
        var service = new RuntimeHttpApiConnectionService(mapper, capability, credentials,
                WorkflowHttpEgressPolicy.productionDefault());
        var controller = new RuntimeHttpApiConnectionInternalController(service);
        var command = new HttpApiConsoleContracts.ConnectionCommand(1, 3L, owner.qualifiedName(),
                36L, "bzjs21", "dev", new HttpApiConsoleContracts.ConnectionSaveRequest(origin, "NONE", null, null));
        var mvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(json)).build();
        mvc.perform(put("/internal/runtime/http-api-connections")
                        .requestAttr(VerifiedInternalServiceAuth.REQUEST_ATTR, new VerifiedInternalServiceAuth(
                                InternalServiceAuthHeaders.CALLER_CONTROL,
                                InternalServiceAuthHeaders.IDENTITY_SOURCE_PLATFORM_SESSION, "bzjs21", "1"))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(command)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(errorCode))
                .andExpect(jsonPath("$.message").value("服务地址被 HTTP 出站策略拒绝，请由管理员核对目标主机白名单；连接未保存"));
        verifyNoInteractions(mapper, credentials);
    }

    @Test void unsignedConnectionSaveCannotReachPolicyOrPersistence() throws Exception {
        var service = mock(RuntimeHttpApiConnectionService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new RuntimeHttpApiConnectionInternalController(service))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(json)).build();
        var command = new HttpApiConsoleContracts.ConnectionCommand(1, 3L, "http-api:bzjs21:count",
                36L, "bzjs21", "dev", new HttpApiConsoleContracts.ConnectionSaveRequest(
                "http://127.0.0.1:8089", "NONE", null, null));
        mvc.perform(put("/internal/runtime/http-api-connections").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(command)))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }
}
