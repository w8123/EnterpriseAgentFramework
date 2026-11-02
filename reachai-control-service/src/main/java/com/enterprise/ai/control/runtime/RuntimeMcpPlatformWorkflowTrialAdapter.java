package com.enterprise.ai.control.runtime;

import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import com.enterprise.ai.control.identity.PlatformAiConnectionService.ConnectionIdentity;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformPermissions;
import com.enterprise.ai.control.mcp.application.port.McpPlatformWorkflowTrialPort;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Delegates to the same attestation as Studio and exposes only this connection's trial status. */
@Component
@RequiredArgsConstructor
final class RuntimeMcpPlatformWorkflowTrialAdapter implements McpPlatformWorkflowTrialPort {
    private final ControlWorkflowReadOnlyTrialController trials;
    private final RuntimeProxyClient runtime;
    private final RuntimeManagementAccess access;
    private final RuntimeMcpActorScope actorScope;

    @Override
    public ResponseEntity<?> run(ConnectionIdentity identity, String trialId, Map<String, Object> body) {
        String traceId = traceId(identity, trialId);
        return actorScope.run(identity.session(), () -> {
            ResponseEntity<Object> response = trials.runPlatformAi(body, traceId);
            if (response == null) return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
            if (response.getStatusCode().is2xxSuccessful()
                    && (!(response.getBody() instanceof Map<?, ?> result) || !traceId.equals(result.get("traceId"))
                    || body == null || !java.util.Objects.equals(body.get("workflowId"), result.get("workflowId")))) {
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
            }
            return response;
        });
    }

    @Override
    public ResponseEntity<?> status(ConnectionIdentity identity, String trialId, String workflowId) {
        String traceId = traceId(identity, trialId);
        if (workflowId == null || !workflowId.matches("[A-Za-z0-9_-]{1,32}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "workflowId is invalid");
        }
        return actorScope.run(identity.session(), () -> {
            PlatformAuthenticatedSession actor = access.require(PlatformPermissions.WORKFLOW_DEBUG);
            access.require(PlatformPermissions.CAPABILITY_INVOKE);
            ResponseEntity<Map<String, Object>> response;
            try { response = runtime.runOpsDetail(traceId); }
            catch (FeignException missing) {
                if (missing.status() == 400 || missing.status() == 404) return unknown(trialId, workflowId);
                throw missing;
            }
            if (response != null && (response.getStatusCode().value() == 400 || response.getStatusCode().value() == 404)) {
                return unknown(trialId, workflowId);
            }
            if (response == null || !response.getStatusCode().is2xxSuccessful()
                    || response.getBody() == null || !(response.getBody().get("summary") instanceof Map<?, ?> summary)
                    || !traceId.equals(summary.get("traceId")) || !workflowId.equals(summary.get("workflowId"))
                    || !"WORKFLOW".equals(summary.get("runType")) || !"STUDIO_READ_ONLY_TRIAL".equals(summary.get("entryType"))
                    || !(summary.get("projectCode") instanceof String projectCode) || projectCode.isBlank()
                    || !(summary.get("status") instanceof String state)) {
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
            }
            access.requireProject(actor, PlatformPermissions.WORKFLOW_DEBUG, null, projectCode);
            access.requireProject(actor, PlatformPermissions.CAPABILITY_INVOKE, null, projectCode);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("trialId", trialId); result.put("workflowId", workflowId); result.put("traceId", traceId);
            result.put("projectCode", projectCode); result.put("status", state);
            for (String key : new String[]{"errorCode", "startedAt", "endedAt"}) {
                Object value = summary.get(key);
                if (value instanceof String) result.put(key, value);
            }
            return ResponseEntity.ok(result);
        });
    }

    private static ResponseEntity<Object> unknown(String trialId, String workflowId) {
        return ResponseEntity.ok(Map.of("trialId", trialId, "workflowId", workflowId, "status", "NOT_FOUND_OR_PENDING",
                "message", "尚无可确认的运行记录；原请求可能仍在准备，请勿换编号直接重试。"));
    }

    static String traceId(ConnectionIdentity identity, String trialId) {
        if (identity == null || identity.connectionId() == null || identity.session() == null
                || identity.session().user() == null || identity.session().user().getId() == null || trialId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "platform AI connection is required");
        }
        try {
            if (!UUID.fromString(trialId).toString().equals(trialId)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "trialId must be a canonical UUID");
        }
        String source = "reachai-platform-trial-v1:" + identity.connectionId() + ":" + identity.session().user().getId() + ":" + trialId;
        try {
            return "mcp-trial-" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8))).substring(0, 32);
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }
}
