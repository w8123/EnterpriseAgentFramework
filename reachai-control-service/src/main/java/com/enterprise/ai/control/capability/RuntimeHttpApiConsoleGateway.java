package com.enterprise.ai.control.capability;

import com.enterprise.ai.common.capability.HttpApiConsoleContracts;
import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import com.enterprise.ai.control.internalauth.InternalServiceAuthSigner;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.function.Supplier;

/** Signs exact request bytes for the Control→Runtime HTTP API Console boundary. */
@Service
@RequiredArgsConstructor
public class RuntimeHttpApiConsoleGateway {
    private static final String CONNECTION = "/internal/runtime/http-api-connections";
    private static final String INVOCATION = "/internal/runtime/http-api-invocations";
    private final RuntimeProxyClient runtime;
    private final InternalServiceAuthSigner signer;
    private final ObjectMapper json;

    public ResponseEntity<Object> readConnection(HttpApiConsoleContracts.ConnectionCommand command, String actor) {
        byte[] body = encode(command);
        return call(() -> runtime.readHttpApiConnection(headers("POST", CONNECTION, command.projectCode(), actor, body), body));
    }

    public ResponseEntity<Object> saveConnection(HttpApiConsoleContracts.ConnectionCommand command, String actor) {
        byte[] body = encode(command);
        return call(() -> runtime.saveHttpApiConnection(headers("PUT", CONNECTION, command.projectCode(), actor, body), body));
    }

    public ResponseEntity<Object> invoke(HttpApiConsoleContracts.InvocationCommand command, String actor) {
        byte[] body = encode(command);
        return call(() -> runtime.invokeHttpApi(headers("POST", INVOCATION, command.projectCode(), actor, body), body));
    }

    public ResponseEntity<Object> getInvocation(String invocationId, String projectCode, String actor) {
        String id;
        try { id = java.util.UUID.fromString(invocationId).toString(); }
        catch (Exception invalid) { throw new IllegalArgumentException("invocationId must be UUID", invalid); }
        String path = INVOCATION + "/" + id;
        return call(() -> runtime.getHttpApiInvocation(id, headers("GET", path, projectCode, actor, new byte[0])));
    }

    private ResponseEntity<Object> call(Supplier<ResponseEntity<Object>> action) {
        try { return action.get(); }
        catch (FeignException failure) {
            int status = failure.status();
            if (status == 404) return ResponseEntity.notFound().build();
            if (status == 400 || status == 409) {
                String code = "HTTP_API_RUNTIME_REJECTED";
                try {
                    var body = json.readTree(failure.contentUTF8());
                    String candidate = body.path("code").asText();
                    if (candidate.matches("[A-Z0-9_]{1,96}")) code = candidate;
                } catch (Exception ignored) { /* no upstream body is forwarded */ }
                String message = status == 409 ? "当前条件已变化，请刷新后重试" : "请求参数无效";
                if (status == 409 && java.util.Set.of("RUNTIME_HTTP_EGRESS_SCHEME_DENIED",
                        "RUNTIME_HTTP_EGRESS_HOST_DENIED", "RUNTIME_HTTP_EGRESS_DNS_DENIED",
                        "RUNTIME_HTTP_EGRESS_REDIRECT_DENIED").contains(code)) {
                    message = "服务地址被 HTTP 出站策略拒绝，请由管理员核对目标主机白名单；连接未保存";
                }
                return ResponseEntity.status(status).body(Map.of("success", false, "code", code,
                        "message", message));
            }
            throw failure;
        }
    }

    private Map<String, String> headers(String method, String path, String projectCode,
                                        String actor, byte[] body) {
        if (!StringUtils.hasText(projectCode) || !StringUtils.hasText(actor)) {
            throw new IllegalArgumentException("project and actor are required");
        }
        return signer.sign(method, path, InternalServiceAuthHeaders.IDENTITY_SOURCE_PLATFORM_SESSION,
                projectCode, actor, body);
    }

    private byte[] encode(Object value) {
        try { return json.writeValueAsBytes(value); }
        catch (Exception invalid) { throw new IllegalArgumentException("HTTP API command cannot be serialized", invalid); }
    }
}
