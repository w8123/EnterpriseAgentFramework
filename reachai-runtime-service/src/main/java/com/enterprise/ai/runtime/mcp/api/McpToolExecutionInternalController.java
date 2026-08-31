package com.enterprise.ai.runtime.mcp.api;

import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.runtime.execution.identity.WorkflowExecutionIdentity;
import com.enterprise.ai.runtime.internalauth.VerifiedInternalServiceAuth;
import com.enterprise.ai.runtime.mcp.application.RuntimeMcpToolExecutionService;
import com.enterprise.ai.runtime.mcp.application.RuntimeMcpToolExecutionService.ExecutionOutcome;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** HMAC-authenticated Control-to-Runtime MCP execution entry. */
@RestController
public class McpToolExecutionInternalController {

    private final RuntimeMcpToolExecutionService service;

    public McpToolExecutionInternalController(RuntimeMcpToolExecutionService service) {
        this.service = service;
    }

    @PostMapping("/internal/runtime/mcp/tool-executions")
    public ResponseEntity<Map<String, Object>> execute(HttpServletRequest httpRequest,
                                                       @RequestBody ToolExecutionRequest request) {
        WorkflowExecutionIdentity identity = requireMcpIdentity(httpRequest, request);
        if (identity == null) return unauthorized();
        if (request == null) return badRequest("MCP_EXECUTION_BODY_REQUIRED", "body is required");
        try {
            ExecutionOutcome outcome = service.execute(
                    new RuntimeMcpToolExecutionService.ExecutionRequest(
                            request.sourceKind(), request.sourceRef(), request.workflowVersionId(),
                            request.toolName(), request.arguments(), request.metadata(), request.timeoutMs()),
                    identity);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", outcome.success());
            body.put("code", outcome.code());
            if (!outcome.output().isEmpty()) body.put("output", outcome.output());
            putIfPresent(body, "runId", outcome.runId());
            putIfPresent(body, "traceId", outcome.traceId());
            return ResponseEntity.ok(body);
        } catch (IllegalArgumentException invalid) {
            return badRequest("MCP_EXECUTION_REQUEST_INVALID", invalid.getMessage());
        } catch (RuntimeException persistenceOrRuntimeFailure) {
            return ResponseEntity.ok(Map.of(
                    "success", false,
                    "code", "MCP_RUNTIME_EXECUTION_BOUNDARY_FAILED"));
        }
    }

    private static WorkflowExecutionIdentity requireMcpIdentity(
            HttpServletRequest httpRequest, ToolExecutionRequest request) {
        Object attribute = httpRequest == null
                ? null : httpRequest.getAttribute(VerifiedInternalServiceAuth.REQUEST_ATTR);
        if (!(attribute instanceof VerifiedInternalServiceAuth verified)
                || !InternalServiceAuthHeaders.IDENTITY_SOURCE_MCP_REMOTE_CLIENT
                .equals(verified.identitySource())
                || !StringUtils.hasText(verified.identityUserId())
                || !StringUtils.hasText(verified.identityTenantId())
                || request == null) {
            return null;
        }
        Map<String, Object> metadata = request.metadata() == null ? Map.of() : request.metadata();
        String bodyTenant = text(metadata.get("tenantId"));
        if (bodyTenant == null || !verified.identityTenantId().equals(bodyTenant)) {
            return null;
        }
        Long mcpClientId = longValue(metadata.get("mcpClientId"));
        if (mcpClientId == null || mcpClientId <= 0
                || !verified.identityUserId().equals("mcp-client-" + mcpClientId)) {
            return null;
        }
        Long projectId = longValue(metadata.get("projectId"));
        String projectCode = text(metadata.get("projectCode"));
        if (projectId == null && !StringUtils.hasText(projectCode)) {
            return null;
        }
        return WorkflowExecutionIdentity.fromMcpRemoteClient(
                verified.identityTenantId(), projectId, projectCode, verified.identityUserId());
    }

    private static ResponseEntity<Map<String, Object>> unauthorized() {
        return ResponseEntity.status(401).body(Map.of(
                "success", false,
                "code", "RUNTIME_INTERNAL_AUTH_REQUIRED"));
    }

    private static ResponseEntity<Map<String, Object>> badRequest(String code, String message) {
        return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "code", code,
                "message", message == null ? code : message));
    }

    private static void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) target.put(key, value);
    }

    private static Long longValue(Object value) {
        if (value instanceof Number number) return number.longValue();
        try {
            return value == null ? null : Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String text(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    public record ToolExecutionRequest(String sourceKind,
                                       String sourceRef,
                                       Long workflowVersionId,
                                       String toolName,
                                       Map<String, Object> arguments,
                                       Map<String, Object> metadata,
                                       Long timeoutMs) {
    }
}
