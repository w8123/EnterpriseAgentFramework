package com.enterprise.ai.control.capability;

import com.enterprise.ai.common.capability.HttpApiConsoleContracts;
import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import com.enterprise.ai.control.internalauth.InternalServiceAuthSigner;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import feign.Request;
import feign.Response;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class RuntimeHttpApiConsoleEgressGatewayTest {
    @Test void egressCodesBecomeSafeActionableMessagesWithoutForwardingUpstreamDetails() throws Exception {
        for (String code : List.of("RUNTIME_HTTP_EGRESS_SCHEME_DENIED", "RUNTIME_HTTP_EGRESS_HOST_DENIED",
                "RUNTIME_HTTP_EGRESS_DNS_DENIED", "RUNTIME_HTTP_EGRESS_REDIRECT_DENIED")) {
            var result = rejection(code);
            assertEquals(409, result.getStatusCode().value());
            var body = (Map<?, ?>) result.getBody();
            assertEquals(code, body.get("code"));
            assertEquals("服务地址被 HTTP 出站策略拒绝，请由管理员核对目标主机白名单；连接未保存", body.get("message"));
            assertFalse(body.toString().contains("private-upstream-detail"));
        }
    }

    @Test void otherConflictsKeepGenericMessageAndNeverExposeUpstreamBody() throws Exception {
        var result = rejection("HTTP_API_CONNECTION_REVISION_CHANGED");
        var body = (Map<?, ?>) result.getBody();
        assertEquals("HTTP_API_CONNECTION_REVISION_CHANGED", body.get("code"));
        assertEquals("当前条件已变化，请刷新后重试", body.get("message"));
        assertFalse(body.toString().contains("private-upstream-detail"));
    }

    @Test void projectDefaultAndOverrideRemovalSignTheirOwnInternalPaths() {
        var runtime = mock(RuntimeProxyClient.class);
        var signer = mock(InternalServiceAuthSigner.class);
        when(signer.sign(any(), any(), any(), any(), any(), any())).thenReturn(Map.of("X-Signed", "1"));
        var command = new HttpApiConsoleContracts.ConnectionCommand(1, 3L, "http-api:bzjs21:count", 36L,
                "bzjs21", "dev", new HttpApiConsoleContracts.ConnectionSaveRequest("https://bzjs21.test", "NONE", null, null));
        var gateway = new RuntimeHttpApiConsoleGateway(runtime, signer, new ObjectMapper());
        gateway.saveProjectDefaultConnection(command, "1");
        gateway.removeConnectionOverride(command, "1");
        verify(signer).sign(eq("PUT"), eq("/internal/runtime/http-api-connections/project-default"), any(), eq("bzjs21"), eq("1"), any());
        verify(signer).sign(eq("POST"), eq("/internal/runtime/http-api-connections/override-removals"), any(), eq("bzjs21"), eq("1"), any());
        verify(runtime).saveHttpApiProjectDefaultConnection(eq(Map.of("X-Signed", "1")), any());
        verify(runtime).removeHttpApiConnectionOverride(eq(Map.of("X-Signed", "1")), any());
    }

    private org.springframework.http.ResponseEntity<Object> rejection(String code) throws Exception {
        var runtime = mock(RuntimeProxyClient.class);
        var signer = mock(InternalServiceAuthSigner.class);
        var json = new ObjectMapper();
        var request = Request.create(Request.HttpMethod.PUT, "http://runtime/internal/runtime/http-api-connections",
                Map.of(), new byte[0], StandardCharsets.UTF_8, null);
        var response = Response.builder().request(request).status(409).headers(Map.of())
                .body(json.writeValueAsString(Map.of("code", code, "message", "private-upstream-detail")),
                        StandardCharsets.UTF_8).build();
        when(runtime.saveHttpApiConnection(any(), any())).thenThrow(FeignException.errorStatus("save", response));
        var command = new HttpApiConsoleContracts.ConnectionCommand(1, 3L, "http-api:bzjs21:count", 36L,
                "bzjs21", "dev", new HttpApiConsoleContracts.ConnectionSaveRequest(
                "http://metadata.google.internal", "NONE", null, 1L));
        return new RuntimeHttpApiConsoleGateway(runtime, signer, json).saveConnection(command, "1");
    }
}
