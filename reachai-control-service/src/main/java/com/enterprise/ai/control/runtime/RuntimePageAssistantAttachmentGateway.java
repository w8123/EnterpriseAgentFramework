package com.enterprise.ai.control.runtime;

import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.internalauth.InternalServiceAuthSigner;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Signs Page Assistant → page copilot sync with the verified platform actor and the project Control authorized,
 * so neither a browser nor a model can choose the project or the recorded publisher.
 */
@Service
@RequiredArgsConstructor
public class RuntimePageAssistantAttachmentGateway {
    static final Set<String> ATTACH_FIELDS = Set.of("agentId", "baseRevision", "createTarget", "modelInstanceId", "replaceWorkflowId");
    private final RuntimeProxyClient runtime;
    private final InternalServiceAuthSigner signer;
    private final ObjectMapper json;

    public ResponseEntity<Object> context(String workflowId, Long projectId, String projectCode, PlatformAuthenticatedSession actor) {
        requireWorkflowId(workflowId);
        Map<String, Object> command = new LinkedHashMap<>();
        command.put("projectId", projectId); command.put("projectCode", projectCode);
        byte[] body = bytes(command);
        String path = "/internal/runtime/workflows/" + workflowId + "/page-assistant/context";
        return call(() -> runtime.pageAssistantAttachContextSigned(workflowId, headers(path, projectCode, actor, body), body));
    }

    public ResponseEntity<Object> attach(String workflowId, Long projectId, String projectCode, Map<String, Object> request,
                                         PlatformAuthenticatedSession actor) {
        requireWorkflowId(workflowId);
        if (request == null || !ATTACH_FIELDS.containsAll(request.keySet())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported page copilot sync field");
        }
        Object revision = request.get("baseRevision");
        boolean createTarget = Boolean.TRUE.equals(request.get("createTarget"));
        boolean hasRevision = revision instanceof String text && text.matches("[a-f0-9]{64}");
        if (revision != null && !hasRevision || hasRevision == createTarget
                || request.get("createTarget") != null && !(request.get("createTarget") instanceof Boolean)) {
            return ResponseEntity.badRequest().body(Map.of("code", "AGENT_DRAFT_REVISION_REQUIRED",
                    "message", "请先读取页面副驾驶当前状态：已存在时提交其修订，尚未创建时提交 createTarget=true"));
        }
        Map<String, Object> command = new LinkedHashMap<>();
        command.put("projectId", projectId); command.put("projectCode", projectCode);
        command.put("agentId", text(request.get("agentId"))); command.put("modelInstanceId", text(request.get("modelInstanceId")));
        command.put("replaceWorkflowId", text(request.get("replaceWorkflowId")));
        command.put("baseRevision", hasRevision ? revision : null); command.put("createTarget", createTarget);
        byte[] body = bytes(command);
        String path = "/internal/runtime/workflows/" + workflowId + "/page-assistant/attach";
        return call(() -> runtime.attachPageAssistantWorkflowToolSigned(workflowId, headers(path, projectCode, actor, body), body));
    }

    private static void requireWorkflowId(String workflowId) {
        // The id is part of the signed path, so it must stay one literal path segment.
        if (workflowId == null || !workflowId.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid Workflow id");
        }
    }

    private static String text(Object value) {
        if (value == null) return null;
        if (!(value instanceof String text) || text.length() > 128 || text.chars().anyMatch(Character::isISOControl)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid page copilot sync field");
        }
        return text.isBlank() ? null : text.trim();
    }

    private byte[] bytes(Map<String, Object> command) {
        try { return json.writeValueAsBytes(command); }
        catch (Exception invalid) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid page copilot sync command"); }
    }

    private Map<String, String> headers(String path, String projectCode, PlatformAuthenticatedSession actor, byte[] body) {
        if (actor == null || actor.user() == null || actor.user().getId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "platform session is required");
        }
        if (projectCode == null || projectCode.isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Page Assistant has no project");
        }
        return signer.sign("POST", path, InternalServiceAuthHeaders.IDENTITY_SOURCE_PLATFORM_SESSION,
                projectCode, String.valueOf(actor.user().getId()), body);
    }

    private ResponseEntity<Object> call(Supplier<ResponseEntity<Object>> action) {
        try { return action.get(); }
        catch (FeignException failure) {
            // Runtime answers attachment refusals with ai-coding-error bodies; keep their code for people and AI.
            if (failure.status() >= 400 && failure.status() < 500) {
                Object body;
                try { body = json.readValue(failure.contentUTF8().getBytes(StandardCharsets.UTF_8), Object.class); }
                catch (Exception unreadable) { body = Map.of("code", failure.status() == 409 ? "AGENT_DRAFT_CHANGED" : "PAGE_COPILOT_SYNC_REJECTED"); }
                return ResponseEntity.status(failure.status()).body(body);
            }
            throw failure;
        }
    }
}
