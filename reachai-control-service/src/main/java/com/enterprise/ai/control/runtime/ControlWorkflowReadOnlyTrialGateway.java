package com.enterprise.ai.control.runtime;

import com.enterprise.ai.common.capability.WorkflowReadOnlyTrialPolicy;
import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import com.enterprise.ai.control.internalauth.InternalServiceAuthSigner;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.util.Map;

/** Signs only Control-attested saved-draft facts, never a browser-supplied identity or graph. */
@Service
@RequiredArgsConstructor
public class ControlWorkflowReadOnlyTrialGateway {
    public static final String PATH = "/internal/runtime/workflows/studio/read-only-trials";

    private final RuntimeProxyClient runtime;
    private final InternalServiceAuthSigner signer;
    private final ObjectMapper json;

    public ResponseEntity<Object> run(WorkflowReadOnlyTrialPolicy.TrialCommand command) {
        try {
            byte[] exactBody = json.writeValueAsBytes(command);
            Map<String, String> headers = signer.sign("POST", PATH,
                    InternalServiceAuthHeaders.IDENTITY_SOURCE_PLATFORM_SESSION,
                    command.projectCode(), command.platformActorId(), exactBody);
            return runtime.runReadOnlyApiDraftTrial(headers, exactBody);
        } catch (FeignException rejected) {
            int status = rejected.status();
            if (status == 400 || status == 403 || status == 409 || status == 503) {
                String code = "HTTP_API_TRIAL_RUNTIME_REJECTED";
                try {
                    String reported = json.readTree(rejected.contentUTF8()).path("errorCode").asText();
                    if (reported.matches("[A-Z0-9_]{1,96}")) code = reported;
                } catch (Exception ignored) { /* never forward an untrusted upstream body */ }
                return ResponseEntity.status(status).body(Map.of("success", false, "errorCode", code,
                        "errorMessage", "试运行条件已变化，请检查草稿、API 来源和项目授权后重试"));
            }
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("success", false,
                    "errorCode", "HTTP_API_TRIAL_RESULT_UNCONFIRMED",
                    "errorMessage", "试运行结果未确认，请先核对 Run/Trace，勿直接重试"));
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("success", false,
                    "errorCode", "HTTP_API_TRIAL_INPUT_INVALID", "errorMessage", "试运行输入无效"));
        }
    }
}
