package com.enterprise.ai.control.runtime;

import com.enterprise.ai.control.identity.ControlAiCodingAccessGuard;
import com.enterprise.ai.control.agentskill.AgentSkillBindingSnapshotAssembler;
import com.enterprise.ai.control.a2a.application.remoteagent.A2aRemoteBindingSnapshotAssembler;
import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import com.enterprise.ai.control.context.PersonalMemoryIdentityResolver;
import com.enterprise.ai.control.identity.PlatformBearerAuthService;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformConsoleAuthInterceptor;
import com.enterprise.ai.control.identity.PlatformPermissions;
import com.enterprise.ai.control.identity.PlatformPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@RestController
public class ControlRuntimePublicController {

    private static final List<String> AGENT_SKILL_ATTESTED_FIELDS = List.of(
            "skillId", "skillVersionId", "publisher", "name", "visibility", "projectCode",
            "version", "sourceSha256", "contentTreeSha256", "sourceRoot",
            "packageManifestJson", "riskReportJson", "hasScripts", "activationMode",
            "scriptPolicy", "required", "enabled", "priority");
    private static final List<String> A2A_REMOTE_ATTESTED_FIELDS = List.of(
            "principalId", "remoteAgentId", "remoteAgentRevisionId", "remoteAgentKey",
            "toolName", "description", "allowedSkillIds", "inputModes", "outputModes",
            "riskLevel", "permissionKey", "timeoutMs", "enabled", "priority");

    private final RuntimeProxyClient runtimeProxyClient;
    private final ControlAiCodingAccessGuard aiCodingAccessGuard;
    private final RuntimeAgentStreamProxy runtimeAgentStreamProxy;
    private final RuntimeTrustedAgentExecutionGateway trustedExecutionGateway;
    private final PlatformBearerAuthService bearerAuthService;
    private final PersonalMemoryIdentityResolver personalMemoryIdentityResolver;
    private final AgentSkillBindingSnapshotAssembler agentSkillBindingSnapshotAssembler;
    private A2aRemoteBindingSnapshotAssembler a2aRemoteBindingSnapshotAssembler;
    private RuntimeManagementAccess runtimeManagementAccess;
    private RuntimeWorkflowReleaseGateway workflowReleaseGateway;
    private RuntimeDebugSessionGateway debugSessionGateway;

    @Autowired
    void setDebugSessionGateway(RuntimeDebugSessionGateway gateway) {
        this.debugSessionGateway = gateway;
    }

    @Autowired
    void setWorkflowReleaseGateway(RuntimeWorkflowReleaseGateway gateway) {
        this.workflowReleaseGateway = gateway;
    }

    @Autowired(required = false)
    void setA2aRemoteBindingSnapshotAssembler(
            A2aRemoteBindingSnapshotAssembler assembler) {
        this.a2aRemoteBindingSnapshotAssembler = assembler;
    }

    @Autowired
    void setRuntimeManagementAccess(RuntimeManagementAccess access) {
        this.runtimeManagementAccess = access;
    }

    ControlRuntimePublicController(RuntimeProxyClient runtimeProxyClient) {
        this(runtimeProxyClient, null, null, null, null, null, null);
    }

    public ControlRuntimePublicController(RuntimeProxyClient runtimeProxyClient,
                                          ControlAiCodingAccessGuard aiCodingAccessGuard) {
        this(runtimeProxyClient, aiCodingAccessGuard, null, null, null, null, null);
    }

    public ControlRuntimePublicController(RuntimeProxyClient runtimeProxyClient,
                                          ControlAiCodingAccessGuard aiCodingAccessGuard,
                                          RuntimeAgentStreamProxy runtimeAgentStreamProxy) {
        this(runtimeProxyClient, aiCodingAccessGuard, runtimeAgentStreamProxy, null, null, null, null);
    }

    public ControlRuntimePublicController(RuntimeProxyClient runtimeProxyClient,
                                          ControlAiCodingAccessGuard aiCodingAccessGuard,
                                          RuntimeAgentStreamProxy runtimeAgentStreamProxy,
                                          RuntimeTrustedAgentExecutionGateway trustedExecutionGateway,
                                          PlatformBearerAuthService bearerAuthService) {
        this(runtimeProxyClient, aiCodingAccessGuard, runtimeAgentStreamProxy,
                trustedExecutionGateway, bearerAuthService, null, null);
    }

    public ControlRuntimePublicController(RuntimeProxyClient runtimeProxyClient,
                                          ControlAiCodingAccessGuard aiCodingAccessGuard,
                                          RuntimeAgentStreamProxy runtimeAgentStreamProxy,
                                          RuntimeTrustedAgentExecutionGateway trustedExecutionGateway,
                                           PlatformBearerAuthService bearerAuthService,
                                           PersonalMemoryIdentityResolver personalMemoryIdentityResolver) {
        this(runtimeProxyClient, aiCodingAccessGuard, runtimeAgentStreamProxy, trustedExecutionGateway,
                bearerAuthService, personalMemoryIdentityResolver, null);
    }

    @Autowired
    public ControlRuntimePublicController(RuntimeProxyClient runtimeProxyClient,
                                          ControlAiCodingAccessGuard aiCodingAccessGuard,
                                          RuntimeAgentStreamProxy runtimeAgentStreamProxy,
                                          RuntimeTrustedAgentExecutionGateway trustedExecutionGateway,
                                          PlatformBearerAuthService bearerAuthService,
                                          PersonalMemoryIdentityResolver personalMemoryIdentityResolver,
                                          AgentSkillBindingSnapshotAssembler agentSkillBindingSnapshotAssembler) {
        this.runtimeProxyClient = runtimeProxyClient;
        this.aiCodingAccessGuard = aiCodingAccessGuard;
        this.runtimeAgentStreamProxy = runtimeAgentStreamProxy;
        this.trustedExecutionGateway = trustedExecutionGateway;
        this.bearerAuthService = bearerAuthService;
        this.personalMemoryIdentityResolver = personalMemoryIdentityResolver;
        this.agentSkillBindingSnapshotAssembler = agentSkillBindingSnapshotAssembler;
    }

    private PlatformAuthenticatedSession requireRuntimePermission(String permission) {
        return runtimeManagementAccess == null ? null : runtimeManagementAccess.require(permission);
    }

    private PlatformAuthenticatedSession requireRuntimeProject(
            String permission,
            Long projectId,
            String projectCode) {
        return runtimeManagementAccess == null
                ? null
                : runtimeManagementAccess.requireProject(permission, projectId, projectCode);
    }

    private void requireRuntimeBodyProject(
            PlatformAuthenticatedSession session,
            String permission,
            Map<String, Object> body) {
        if (runtimeManagementAccess != null) {
            runtimeManagementAccess.requireBodyProject(session, permission, body);
        }
    }

    private void requireRuntimeResponseProject(
            PlatformAuthenticatedSession session,
            String permission,
            ResponseEntity<?> response) {
        if (runtimeManagementAccess != null) {
            runtimeManagementAccess.requireResponseProject(session, permission, response);
        }
    }

    private void requireWorkflowPayload(
            PlatformAuthenticatedSession session,
            String permission,
            Map<String, Object> body) {
        if (runtimeManagementAccess == null) return;
        String workflowId = normalizedText(body == null ? null : body.get("workflowId"));
        if (StringUtils.hasText(workflowId)) {
            requireRuntimeResponseProject(
                    session, permission, runtimeProxyClient.getWorkflow(workflowId));
            return;
        }
        requireRuntimeBodyProject(session, permission, body);
    }

    private void requireAgentPayload(
            PlatformAuthenticatedSession session,
            String permission,
            Map<String, Object> body) {
        if (runtimeManagementAccess == null) return;
        String agentId = normalizedText(body == null ? null : body.get("targetId"));
        if (!StringUtils.hasText(agentId)) {
            agentId = normalizedText(body == null ? null : body.get("agentId"));
        }
        if (StringUtils.hasText(agentId)) {
            requireRuntimeResponseProject(
                    session, permission, runtimeProxyClient.getAgent(agentId));
            return;
        }
        requireRuntimeBodyProject(session, permission, body);
    }

    @PostMapping("/api/runtime/agents/execute")
    public ResponseEntity<Map<String, Object>> executeAgent(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> body) {
        return executeAgentWithServerIdentity(authorization, body, false);
    }

    @PostMapping("/api/runtime/agents/execute/detailed")
    public ResponseEntity<Map<String, Object>> executeAgentDetailed(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> body) {
        return executeAgentWithServerIdentity(authorization, body, true);
    }

    @PostMapping(value = "/api/runtime/agents/execute/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> executeAgentStream(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> body) {
        return executeAgentStreamWithServerIdentity(authorization, body);
    }

    @DeleteMapping("/api/runtime/agents/sessions/{sessionId}")
    public ResponseEntity<Void> clearAgentSession(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String sessionId) {
        Optional<PlatformPrincipal> user = authenticatedAgentUser(authorization);
        if (user.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (trustedExecutionGateway == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        return trustedExecutionGateway.clearTrusted(
                sessionId, "AGENT", "default", trustedAgentUserId(user.get()));
    }

    @GetMapping("/api/runtime/agents/route-evaluation")
    public ResponseEntity<Map<String, Object>> routeEvaluation(
            @RequestParam(defaultValue = "30") int days) {
        requireRuntimeProject(PlatformPermissions.AGENT_EVALUATE, null, null);
        return runtimeProxyClient.routeEvaluation(days);
    }

    @GetMapping("/api/traces/{traceId}")
    public ResponseEntity<Map<String, Object>> getTrace(@PathVariable String traceId) {
        PlatformAuthenticatedSession session = requireRuntimePermission(PlatformPermissions.RUNOPS_READ);
        ResponseEntity<Map<String, Object>> response = runtimeProxyClient.getTrace(traceId);
        requireRuntimeResponseProject(session, PlatformPermissions.RUNOPS_READ, response);
        return response;
    }

    @GetMapping("/api/traces/recent")
    public ResponseEntity<Map<String, Object>> listRecentTraces(
            @RequestParam(required = false) String userId,
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(defaultValue = "20") int limit) {
        requireRuntimeProject(PlatformPermissions.RUNOPS_READ, null, null);
        return runtimeProxyClient.listRecentTraces(userId, days, limit);
    }

    @GetMapping("/api/runops/traces/{traceId}")
    public ResponseEntity<Map<String, Object>> runOpsDetail(@PathVariable String traceId) {
        PlatformAuthenticatedSession session = requireRuntimePermission(PlatformPermissions.RUNOPS_READ);
        ResponseEntity<Map<String, Object>> response = runtimeProxyClient.runOpsDetail(traceId);
        requireRuntimeResponseProject(session, PlatformPermissions.RUNOPS_READ, response);
        return response;
    }

    @GetMapping("/api/runops/traces/recent")
    public ResponseEntity<List<Map<String, Object>>> runOpsRecent(
            @RequestParam(required = false) String projectCode,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String runType,
            @RequestParam(required = false) String entryType,
            @RequestParam(required = false) String agentId,
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "7") int days) {
        requireRuntimeProject(PlatformPermissions.RUNOPS_READ, null, projectCode);
        return runtimeProxyClient.runOpsRecent(
                projectCode, status, runType, entryType, agentId, userId, keyword, limit, days);
    }

    @GetMapping("/api/runops/diagnostics")
    public ResponseEntity<Map<String, Object>> runOpsDiagnostics(
            @RequestParam(required = false) String projectCode,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String runType,
            @RequestParam(required = false) String entryType,
            @RequestParam(required = false) String agentId,
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "7") int days) {
        requireRuntimeProject(PlatformPermissions.RUNOPS_READ, null, projectCode);
        return runtimeProxyClient.runOpsDiagnostics(
                projectCode, status, runType, entryType, agentId, userId, keyword, limit, days);
    }

    @GetMapping("/api/trace-center/guard-decisions")
    public ResponseEntity<Object> listGuardDecisions(
            @RequestParam(required = false) String traceId,
            @RequestParam(required = false) String decisionType,
            @RequestParam(required = false) String targetKind,
            @RequestParam(required = false) String targetName,
            @RequestParam(required = false) String decision,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "200") int limit) {
        if (runtimeManagementAccess != null && StringUtils.hasText(traceId)) {
            PlatformAuthenticatedSession session = requireRuntimePermission(PlatformPermissions.RUNOPS_READ);
            requireRuntimeResponseProject(
                    session,
                    PlatformPermissions.RUNOPS_READ,
                    runtimeProxyClient.runOpsDetail(traceId));
        } else {
            requireRuntimeProject(PlatformPermissions.RUNOPS_READ, null, null);
        }
        return runtimeProxyClient.listGuardDecisions(
                traceId, decisionType, targetKind, targetName, decision, from, to, limit);
    }

    @GetMapping("/api/runops/traces/{traceId}/compare/{candidateTraceId}")
    public ResponseEntity<Map<String, Object>> runOpsCompare(@PathVariable String traceId,
                                                             @PathVariable String candidateTraceId) {
        if (runtimeManagementAccess != null) {
            PlatformAuthenticatedSession session = requireRuntimePermission(PlatformPermissions.RUNOPS_READ);
            requireRuntimeResponseProject(
                    session, PlatformPermissions.RUNOPS_READ, runtimeProxyClient.runOpsDetail(traceId));
            requireRuntimeResponseProject(
                    session, PlatformPermissions.RUNOPS_READ, runtimeProxyClient.runOpsDetail(candidateTraceId));
        }
        return runtimeProxyClient.runOpsCompare(traceId, candidateTraceId);
    }

    @PostMapping("/api/runops/traces/{traceId}/replay")
    public ResponseEntity<Map<String, Object>> runOpsReplay(@PathVariable String traceId,
                                                            @RequestBody Map<String, Object> body) {
        if (runtimeManagementAccess != null) {
            PlatformAuthenticatedSession session = requireRuntimePermission(PlatformPermissions.RUNOPS_OPERATE);
            requireRuntimeResponseProject(
                    session, PlatformPermissions.RUNOPS_OPERATE, runtimeProxyClient.runOpsDetail(traceId));
        }
        return runtimeProxyClient.runOpsReplay(traceId, body);
    }

    @PostMapping({"/api/v1/agents/{key}/chat", "/gateway/agents/{key}/chat"})
    public ResponseEntity<Object> gatewayAgentChat(@PathVariable String key,
                                                   @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> runtimeBody = new LinkedHashMap<>();
        if (body != null) {
            runtimeBody.putAll(body);
        }
        runtimeBody.put("agentId", key);
        runtimeBody.putIfAbsent("intentHint", "AGENT_GATEWAY_CHAT");
        runtimeBody.putIfAbsent("entryType", "GATEWAY");
        ResponseEntity<Map<String, Object>> response = runtimeProxyClient.executeAgent(runtimeBody);
        return ResponseEntity.status(response.getStatusCode())
                .headers(response.getHeaders())
                .body(response.getBody());
    }

    @GetMapping("/gateway/catalog")
    public ResponseEntity<Object> gatewayCatalog(@RequestParam(required = false) Long projectId) {
        ResponseEntity<Object> response = runtimeProxyClient.listAgents(projectId, null);
        if (!response.getStatusCode().is2xxSuccessful()) {
            return response;
        }
        Map<String, Object> catalog = new LinkedHashMap<>();
        catalog.put("agents", toGatewayAgentItems(response.getBody()));
        catalog.put("capabilities", List.of());
        return ResponseEntity.status(response.getStatusCode())
                .headers(response.getHeaders())
                .body(catalog);
    }

    @GetMapping("/api/workflows")
    public ResponseEntity<Object> listWorkflows(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String projectCode,
            @RequestParam(required = false) String workflowKind,
            @RequestParam(required = false) String definitionAuthority,
            @RequestParam(required = false) String status) {
        requireRuntimeProject(PlatformPermissions.WORKFLOW_READ, projectId, projectCode);
        return runtimeProxyClient.listWorkflows(
                projectId, projectCode, workflowKind, definitionAuthority, status);
    }

    @GetMapping("/api/workflows/search")
    public ResponseEntity<Object> searchWorkflows(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String projectCode,
            @RequestParam(required = false) String workflowKind,
            @RequestParam(required = false) String definitionAuthority,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer current,
            @RequestParam(required = false) Integer size) {
        requireRuntimeProject(PlatformPermissions.WORKFLOW_READ, projectId, projectCode);
        return runtimeProxyClient.searchWorkflows(
                projectId, projectCode, workflowKind, definitionAuthority,
                status, keyword, current, size);
    }

    @PostMapping("/api/workflows")
    public ResponseEntity<Object> createWorkflow(@RequestBody Map<String, Object> body) {
        PlatformAuthenticatedSession session = requireRuntimePermission(PlatformPermissions.WORKFLOW_WRITE);
        requireRuntimeBodyProject(session, PlatformPermissions.WORKFLOW_WRITE, body);
        return runtimeProxyClient.createWorkflow(body);
    }

    @GetMapping("/api/workflows/{id}")
    public ResponseEntity<Object> getWorkflow(@PathVariable String id) {
        PlatformAuthenticatedSession session = requireRuntimePermission(PlatformPermissions.WORKFLOW_READ);
        ResponseEntity<Object> response = runtimeProxyClient.getWorkflow(id);
        requireRuntimeResponseProject(session, PlatformPermissions.WORKFLOW_READ, response);
        return response;
    }

    @PutMapping("/api/workflows/{id}")
    public ResponseEntity<Object> updateWorkflow(@PathVariable String id,
                                                 @RequestBody Map<String, Object> body) {
        if (runtimeManagementAccess != null) {
            PlatformAuthenticatedSession session = requireRuntimePermission(PlatformPermissions.WORKFLOW_WRITE);
            ResponseEntity<Object> current = runtimeProxyClient.getWorkflow(id);
            requireRuntimeResponseProject(session, PlatformPermissions.WORKFLOW_WRITE, current);
            if (!current.getStatusCode().is2xxSuccessful()) return current;
            if (body != null && (body.containsKey("projectId") || body.containsKey("projectCode"))) {
                requireRuntimeBodyProject(session, PlatformPermissions.WORKFLOW_WRITE, body);
            }
        }
        return runtimeProxyClient.updateWorkflow(id, body);
    }

    @DeleteMapping("/api/workflows/{id}")
    public ResponseEntity<Object> deleteWorkflow(@PathVariable String id) {
        if (runtimeManagementAccess != null) {
            PlatformAuthenticatedSession session = requireRuntimePermission(PlatformPermissions.WORKFLOW_WRITE);
            ResponseEntity<Object> current = runtimeProxyClient.getWorkflow(id);
            requireRuntimeResponseProject(session, PlatformPermissions.WORKFLOW_WRITE, current);
            if (!current.getStatusCode().is2xxSuccessful()) return current;
        }
        return runtimeProxyClient.deleteWorkflow(id);
    }

    @GetMapping("/api/workflows/graph-node-types")
    public ResponseEntity<Object> graphNodeTypes() {
        requireRuntimePermission(PlatformPermissions.WORKFLOW_READ);
        return runtimeProxyClient.graphNodeTypes();
    }

    @PostMapping("/api/workflows/runtime-validation")
    public ResponseEntity<Object> validateWorkflowRuntime(@RequestBody Map<String, Object> body) {
        PlatformAuthenticatedSession session = requireRuntimePermission(PlatformPermissions.WORKFLOW_WRITE);
        requireWorkflowPayload(session, PlatformPermissions.WORKFLOW_WRITE, body);
        return runtimeProxyClient.validateWorkflowRuntime(body);
    }

    @GetMapping("/api/workflows/{id}/working-copy")
    public ResponseEntity<Object> workflowWorkingCopy(@PathVariable String id) {
        if (runtimeManagementAccess != null) {
            PlatformAuthenticatedSession session = requireRuntimePermission(PlatformPermissions.WORKFLOW_READ);
            requireRuntimeResponseProject(
                    session, PlatformPermissions.WORKFLOW_READ, runtimeProxyClient.getWorkflow(id));
        }
        return runtimeProxyClient.workflowWorkingCopy(id);
    }

    @PutMapping("/api/workflows/{id}/working-copy")
    public ResponseEntity<Object> saveWorkflowWorkingCopy(@PathVariable String id,
                                                          @RequestBody Map<String, Object> body) {
        if (runtimeManagementAccess != null) {
            PlatformAuthenticatedSession session = requireRuntimePermission(PlatformPermissions.WORKFLOW_WRITE);
            requireRuntimeResponseProject(
                    session, PlatformPermissions.WORKFLOW_WRITE, runtimeProxyClient.getWorkflow(id));
        }
        return runtimeProxyClient.saveWorkflowWorkingCopy(id, body);
    }

    @PostMapping("/api/workflows/studio/debug-node")
    public ResponseEntity<Object> debugWorkflowNode(@RequestBody Map<String, Object> body) {
        PlatformAuthenticatedSession session = requireRuntimePermission(PlatformPermissions.WORKFLOW_DEBUG);
        requireWorkflowPayload(session, PlatformPermissions.WORKFLOW_DEBUG, body);
        return runtimeProxyClient.debugWorkflowNode(body);
    }

    @PostMapping("/api/workflows/studio/debug-run")
    public ResponseEntity<Object> debugWorkflowRun(@RequestBody Map<String, Object> body) {
        PlatformAuthenticatedSession session = requireRuntimePermission(PlatformPermissions.WORKFLOW_DEBUG);
        requireWorkflowPayload(session, PlatformPermissions.WORKFLOW_DEBUG, body);
        return runtimeProxyClient.debugWorkflowRun(body);
    }

    @PostMapping("/api/workflows/studio/proposals/generate")
    public ResponseEntity<Object> generateWorkflowProposal(@RequestBody Map<String, Object> body) {
        PlatformAuthenticatedSession session = requireRuntimePermission(PlatformPermissions.WORKFLOW_WRITE);
        requireWorkflowPayload(session, PlatformPermissions.WORKFLOW_WRITE, body);
        return runtimeProxyClient.generateWorkflowProposal(body);
    }

    @PostMapping("/api/workflows/studio/proposals/edit")
    public ResponseEntity<Object> editWorkflowProposal(@RequestBody Map<String, Object> body) {
        PlatformAuthenticatedSession session = requireRuntimePermission(PlatformPermissions.WORKFLOW_WRITE);
        requireWorkflowPayload(session, PlatformPermissions.WORKFLOW_WRITE, body);
        return runtimeProxyClient.editWorkflowProposal(body);
    }

    @PostMapping("/api/workflows/ai-coding/workflows")
    public ResponseEntity<Object> createWorkflowAiCodingWorkflow(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = ControlAiCodingAccessGuard.AI_CODING_HEADER, required = false) String aiCodingKey) {
        if (aiCodingAccessGuard != null) {
            aiCodingAccessGuard.requireWorkflowCreateAccess(body, aiCodingKey);
        }
        return runtimeProxyClient.createWorkflowAiCodingWorkflow(body);
    }

    @GetMapping("/api/workflows/{workflowId}/ai-coding/context")
    public ResponseEntity<Object> workflowAiCodingContext(@PathVariable String workflowId) {
        return runtimeProxyClient.workflowAiCodingContext(workflowId);
    }

    @PutMapping("/api/workflows/{workflowId}/ai-coding/resource-bindings")
    public ResponseEntity<Object> replaceWorkflowAiCodingResourceBindings(
            @PathVariable String workflowId,
            @RequestBody Map<String, Object> body) {
        return runtimeProxyClient.replaceWorkflowAiCodingResourceBindings(workflowId, body);
    }

    @PostMapping("/api/workflows/{workflowId}/ai-coding/validate")
    public ResponseEntity<Object> validateWorkflowAiCoding(@PathVariable String workflowId,
                                                           @RequestBody Map<String, Object> body) {
        return runtimeProxyClient.validateWorkflowAiCoding(workflowId, body);
    }

    @PostMapping("/api/workflows/{workflowId}/ai-coding/patch")
    public ResponseEntity<Object> patchWorkflowAiCoding(@PathVariable String workflowId,
                                                        @RequestBody Map<String, Object> body) {
        return runtimeProxyClient.patchWorkflowAiCoding(workflowId, body);
    }

    @PostMapping("/api/workflows/{workflowId}/ai-coding/run")
    public ResponseEntity<Object> runWorkflowAiCoding(@PathVariable String workflowId,
                                                      @RequestBody Map<String, Object> body) {
        return runtimeProxyClient.runWorkflowAiCoding(workflowId, body);
    }

    @GetMapping("/api/workflows/{workflowId}/ai-coding/versions")
    public ResponseEntity<Object> workflowAiCodingVersions(@PathVariable String workflowId) {
        return runtimeProxyClient.workflowAiCodingVersions(workflowId);
    }

    @PostMapping("/api/workflows/{workflowId}/ai-coding/publish")
    public ResponseEntity<Object> publishWorkflowAiCoding(@PathVariable String workflowId,
                                                          @RequestBody Map<String, Object> body) {
        return runtimeProxyClient.publishWorkflowAiCoding(workflowId, body);
    }

    @GetMapping("/api/workflows/{workflowId}/ai-coding/runs")
    public ResponseEntity<Object> workflowAiCodingRuns(@PathVariable String workflowId,
                                                       @RequestParam(required = false) Integer limit,
                                                       @RequestParam(required = false) Integer days) {
        return runtimeProxyClient.workflowAiCodingRuns(workflowId, limit, days);
    }

    @GetMapping("/api/workflows/{workflowId}/ai-coding/runs/{traceId}")
    public ResponseEntity<Object> workflowAiCodingRunDetail(@PathVariable String workflowId,
                                                            @PathVariable String traceId) {
        return runtimeProxyClient.workflowAiCodingRunDetail(workflowId, traceId);
    }

    @GetMapping("/api/workflows/{workflowId}/ai-coding/page-assistant/catalog")
    public ResponseEntity<Object> workflowAiCodingPageAssistantCatalog(@PathVariable String workflowId) {
        return runtimeProxyClient.workflowAiCodingPageAssistantCatalog(workflowId);
    }

    @PostMapping("/api/workflows/{workflowId}/ai-coding/page-assistant/validate")
    public ResponseEntity<Object> validateWorkflowAiCodingPageAssistant(@PathVariable String workflowId,
                                                                        @RequestBody Map<String, Object> body) {
        return runtimeProxyClient.validateWorkflowAiCodingPageAssistant(workflowId, body);
    }

    @PostMapping("/api/workflows/{workflowId}/ai-coding/page-assistant/smoke-test")
    public ResponseEntity<Object> smokeTestWorkflowAiCodingPageAssistant(@PathVariable String workflowId,
                                                                         @RequestBody Map<String, Object> body) {
        return runtimeProxyClient.smokeTestWorkflowAiCodingPageAssistant(workflowId, body);
    }

    @GetMapping("/api/workflows/{workflowId}/versions")
    public ResponseEntity<Object> listWorkflowVersions(@PathVariable String workflowId) {
        if (runtimeManagementAccess != null) {
            PlatformAuthenticatedSession session = requireRuntimePermission(PlatformPermissions.WORKFLOW_READ);
            requireRuntimeResponseProject(
                    session, PlatformPermissions.WORKFLOW_READ, runtimeProxyClient.getWorkflow(workflowId));
        }
        return runtimeProxyClient.listWorkflowVersions(workflowId);
    }

    @PostMapping("/api/workflows/{workflowId}/versions/publish")
    public ResponseEntity<Object> publishWorkflowVersion(@PathVariable String workflowId,
                                                        @RequestBody Map<String, Object> body) {
        PlatformAuthenticatedSession session = requireRuntimePermission(PlatformPermissions.WORKFLOW_PUBLISH);
        requireRuntimeResponseProject(
                session, PlatformPermissions.WORKFLOW_PUBLISH, runtimeProxyClient.getWorkflow(workflowId));
        return workflowReleaseGateway.publish(workflowId, body, session);
    }

    @PostMapping("/api/workflows/{workflowId}/versions/validate")
    public ResponseEntity<Object> validateWorkflowVersion(@PathVariable String workflowId) {
        if (runtimeManagementAccess != null) {
            PlatformAuthenticatedSession session = requireRuntimePermission(PlatformPermissions.WORKFLOW_WRITE);
            requireRuntimeResponseProject(
                    session, PlatformPermissions.WORKFLOW_WRITE, runtimeProxyClient.getWorkflow(workflowId));
        }
        return runtimeProxyClient.validateWorkflowVersion(workflowId);
    }

    @PostMapping("/api/workflows/{workflowId}/versions/{versionId}/rollback")
    public ResponseEntity<Object> rollbackWorkflowVersion(@PathVariable String workflowId,
                                                         @PathVariable Long versionId,
                                                         @RequestBody(required = false) Map<String, Object> body) {
        PlatformAuthenticatedSession session = requireRuntimePermission(PlatformPermissions.WORKFLOW_PUBLISH);
        requireRuntimeResponseProject(
                session, PlatformPermissions.WORKFLOW_PUBLISH, runtimeProxyClient.getWorkflow(workflowId));
        return workflowReleaseGateway.rollback(workflowId, versionId, body, session);
    }

    @PostMapping("/api/workflows/{id}/page-assistant/attach-tool")
    public ResponseEntity<Object> attachPageAssistantWorkflowTool(@PathVariable String id,
                                                                  @RequestBody Map<String, Object> body) {
        if (runtimeManagementAccess != null) {
            PlatformAuthenticatedSession session = requireRuntimePermission(PlatformPermissions.WORKFLOW_WRITE);
            requireRuntimeResponseProject(
                    session, PlatformPermissions.WORKFLOW_WRITE, runtimeProxyClient.getWorkflow(id));
        }
        return runtimeProxyClient.attachPageAssistantWorkflowTool(id, body);
    }

    @GetMapping("/api/workflows/credentials")
    public ResponseEntity<Object> listWorkflowCredentials(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String projectCode) {
        requireRuntimeProject(
                PlatformPermissions.WORKFLOW_CREDENTIAL_MANAGE, projectId, projectCode);
        return runtimeProxyClient.listWorkflowCredentials(projectId, projectCode);
    }

    @PostMapping("/api/workflows/credentials")
    public ResponseEntity<Object> createWorkflowCredential(@RequestBody Map<String, Object> body) {
        PlatformAuthenticatedSession session = requireRuntimePermission(
                PlatformPermissions.WORKFLOW_CREDENTIAL_MANAGE);
        requireRuntimeBodyProject(session, PlatformPermissions.WORKFLOW_CREDENTIAL_MANAGE, body);
        return runtimeProxyClient.createWorkflowCredential(body);
    }

    @PutMapping("/api/workflows/credentials/{id}")
    public ResponseEntity<Object> updateWorkflowCredential(@PathVariable Long id,
                                                          @RequestBody Map<String, Object> body) {
        requireRuntimeProject(PlatformPermissions.WORKFLOW_CREDENTIAL_MANAGE, null, null);
        return runtimeProxyClient.updateWorkflowCredential(id, body);
    }

    @DeleteMapping("/api/workflows/credentials/{id}")
    public ResponseEntity<Object> deleteWorkflowCredential(@PathVariable Long id) {
        requireRuntimeProject(PlatformPermissions.WORKFLOW_CREDENTIAL_MANAGE, null, null);
        return runtimeProxyClient.deleteWorkflowCredential(id);
    }

    @GetMapping("/api/runtime/evals/datasets")
    public ResponseEntity<Object> listEvalDatasets(@RequestParam(required = false) String agentId) {
        if (StringUtils.hasText(agentId) && runtimeManagementAccess != null) {
            PlatformAuthenticatedSession session = requireRuntimePermission(PlatformPermissions.AGENT_EVALUATE);
            requireRuntimeResponseProject(
                    session, PlatformPermissions.AGENT_EVALUATE, runtimeProxyClient.getAgent(agentId));
        } else {
            // The legacy collection has no project filter. Do not expose a cross-project list
            // to a project-scoped role when the target Agent is unknown.
            requireRuntimeProject(PlatformPermissions.AGENT_EVALUATE, null, null);
        }
        return runtimeProxyClient.listEvalDatasets(agentId);
    }

    @PostMapping("/api/runtime/evals/datasets")
    public ResponseEntity<Object> createEvalDataset(@RequestBody Map<String, Object> body) {
        PlatformAuthenticatedSession session = requireRuntimePermission(PlatformPermissions.AGENT_EVALUATE);
        requireAgentPayload(session, PlatformPermissions.AGENT_EVALUATE, body);
        return runtimeProxyClient.createEvalDataset(body);
    }

    @PostMapping("/api/runtime/evals/datasets/{datasetId}/cases/import")
    public ResponseEntity<Object> importEvalCases(@PathVariable Long datasetId,
                                                  @RequestBody Map<String, Object> body) {
        // Legacy dataset responses do not carry a canonical project. Keep opaque-id
        // mutations global-only until the compatibility API is retired.
        requireRuntimeProject(PlatformPermissions.AGENT_EVALUATE, null, null);
        return runtimeProxyClient.importEvalCases(datasetId, body);
    }

    @GetMapping("/api/runtime/evals/datasets/{datasetId}/cases")
    public ResponseEntity<Object> listEvalCases(@PathVariable Long datasetId) {
        requireRuntimeProject(PlatformPermissions.AGENT_EVALUATE, null, null);
        return runtimeProxyClient.listEvalCases(datasetId);
    }

    @PostMapping("/api/runtime/evals/runs")
    public ResponseEntity<Object> startEvalRun(@RequestBody Map<String, Object> body) {
        PlatformAuthenticatedSession session = requireRuntimePermission(PlatformPermissions.AGENT_EVALUATE);
        requireAgentPayload(session, PlatformPermissions.AGENT_EVALUATE, body);
        return runtimeProxyClient.startEvalRun(body);
    }

    @GetMapping("/api/runtime/evals/runs/{runId}")
    public ResponseEntity<Object> getEvalRun(@PathVariable Long runId) {
        requireRuntimeProject(PlatformPermissions.AGENT_EVALUATE, null, null);
        return runtimeProxyClient.getEvalRun(runId);
    }

    @GetMapping("/api/runtime/evals/runs/{runId}/results")
    public ResponseEntity<Object> listEvalRunResults(@PathVariable Long runId) {
        requireRuntimeProject(PlatformPermissions.AGENT_EVALUATE, null, null);
        return runtimeProxyClient.listEvalRunResults(runId);
    }

    @GetMapping("/api/runtime/evals/v2/datasets")
    public ResponseEntity<Object> listEvalOpsDatasets(@RequestParam(required = false) String targetId) {
        if (StringUtils.hasText(targetId) && runtimeManagementAccess != null) {
            PlatformAuthenticatedSession session = requireRuntimePermission(PlatformPermissions.AGENT_EVALUATE);
            requireRuntimeResponseProject(
                    session, PlatformPermissions.AGENT_EVALUATE, runtimeProxyClient.getAgent(targetId));
        } else {
            requireRuntimeProject(PlatformPermissions.AGENT_EVALUATE, null, null);
        }
        return runtimeProxyClient.listEvalOpsDatasets("default", targetId);
    }

    @PostMapping("/api/runtime/evals/v2/datasets")
    public ResponseEntity<Object> createEvalOpsDataset(HttpServletRequest request,
                                                       @RequestBody Map<String, Object> body) {
        PlatformAuthenticatedSession session = requireRuntimePermission(PlatformPermissions.AGENT_EVALUATE);
        requireAgentPayload(session, PlatformPermissions.AGENT_EVALUATE, body);
        return runtimeProxyClient.createEvalOpsDataset(attestedEvalBody(request, body));
    }

    @GetMapping("/api/runtime/evals/v2/datasets/{datasetId}")
    public ResponseEntity<Object> getEvalOpsDataset(@PathVariable Long datasetId) {
        PlatformAuthenticatedSession session = requireRuntimePermission(PlatformPermissions.AGENT_EVALUATE);
        ResponseEntity<Object> response = runtimeProxyClient.getEvalOpsDataset(datasetId);
        requireRuntimeResponseProject(session, PlatformPermissions.AGENT_EVALUATE, response);
        return response;
    }

    @PostMapping("/api/runtime/evals/v2/datasets/{datasetId}/versions")
    public ResponseEntity<Object> createEvalOpsDatasetVersion(
            HttpServletRequest request,
            @PathVariable Long datasetId,
            @RequestBody Map<String, Object> body) {
        if (runtimeManagementAccess != null) {
            PlatformAuthenticatedSession session = requireRuntimePermission(PlatformPermissions.AGENT_EVALUATE);
            requireRuntimeResponseProject(
                    session,
                    PlatformPermissions.AGENT_EVALUATE,
                    runtimeProxyClient.getEvalOpsDataset(datasetId));
        }
        return runtimeProxyClient.createEvalOpsDatasetVersion(datasetId, attestedEvalBody(request, body));
    }

    @PostMapping("/api/runtime/evals/v2/datasets/{datasetId}/versions/from-trace")
    public ResponseEntity<Object> createEvalOpsDatasetVersionFromTrace(
            HttpServletRequest request,
            @PathVariable Long datasetId,
            @RequestBody Map<String, Object> body) {
        if (runtimeManagementAccess != null) {
            PlatformAuthenticatedSession session = requireRuntimePermission(PlatformPermissions.AGENT_EVALUATE);
            requireRuntimeResponseProject(
                    session,
                    PlatformPermissions.AGENT_EVALUATE,
                    runtimeProxyClient.getEvalOpsDataset(datasetId));
        }
        return runtimeProxyClient.createEvalOpsDatasetVersionFromTrace(datasetId, attestedEvalBody(request, body));
    }

    @GetMapping("/api/runtime/evals/v2/dataset-versions/{versionId}")
    public ResponseEntity<Object> getEvalOpsDatasetVersion(@PathVariable Long versionId) {
        // This response has datasetId but not projectCode and Runtime currently exposes no
        // parent lookup by version id. Fail closed for project-scoped sessions.
        requireRuntimeProject(PlatformPermissions.AGENT_EVALUATE, null, null);
        return runtimeProxyClient.getEvalOpsDatasetVersion(versionId);
    }

    @GetMapping("/api/runtime/evals/v2/evaluator-suites")
    public ResponseEntity<Object> listEvalOpsEvaluatorSuites() {
        requireRuntimeProject(PlatformPermissions.AGENT_EVALUATE, null, null);
        return runtimeProxyClient.listEvalOpsEvaluatorSuites("default");
    }

    @PostMapping("/api/runtime/evals/v2/evaluator-suites")
    public ResponseEntity<Object> createEvalOpsEvaluatorSuite(
            HttpServletRequest request,
            @RequestBody Map<String, Object> body) {
        requireRuntimeProject(PlatformPermissions.AGENT_EVALUATE, null, null);
        return runtimeProxyClient.createEvalOpsEvaluatorSuite(attestedEvalBody(request, body));
    }

    @GetMapping("/api/runtime/evals/v2/experiments")
    public ResponseEntity<Object> listEvalOpsExperiments(@RequestParam(required = false) String targetId) {
        if (StringUtils.hasText(targetId) && runtimeManagementAccess != null) {
            PlatformAuthenticatedSession session = requireRuntimePermission(PlatformPermissions.AGENT_EVALUATE);
            requireRuntimeResponseProject(
                    session, PlatformPermissions.AGENT_EVALUATE, runtimeProxyClient.getAgent(targetId));
        } else {
            requireRuntimeProject(PlatformPermissions.AGENT_EVALUATE, null, null);
        }
        return runtimeProxyClient.listEvalOpsExperiments("default", targetId);
    }

    @PostMapping("/api/runtime/evals/v2/experiments")
    public ResponseEntity<Object> createEvalOpsExperiment(
            HttpServletRequest request,
            @RequestBody Map<String, Object> body) {
        PlatformAuthenticatedSession session = requireRuntimePermission(PlatformPermissions.AGENT_EVALUATE);
        requireAgentPayload(session, PlatformPermissions.AGENT_EVALUATE, body);
        return runtimeProxyClient.createEvalOpsExperiment(attestedEvalBody(request, body));
    }

    @GetMapping("/api/runtime/evals/v2/experiments/{experimentId}")
    public ResponseEntity<Object> getEvalOpsExperiment(@PathVariable Long experimentId) {
        PlatformAuthenticatedSession session = requireRuntimePermission(PlatformPermissions.AGENT_EVALUATE);
        ResponseEntity<Object> response = runtimeProxyClient.getEvalOpsExperiment(experimentId);
        requireRuntimeResponseProject(session, PlatformPermissions.AGENT_EVALUATE, response);
        return response;
    }

    @GetMapping("/api/runtime/evals/v2/experiments/{experimentId}/items")
    public ResponseEntity<Object> listEvalOpsExperimentItems(
            @PathVariable Long experimentId,
            @RequestParam(required = false) Long variantId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int pageSize) {
        if (runtimeManagementAccess != null) {
            PlatformAuthenticatedSession session = requireRuntimePermission(PlatformPermissions.AGENT_EVALUATE);
            requireRuntimeResponseProject(
                    session,
                    PlatformPermissions.AGENT_EVALUATE,
                    runtimeProxyClient.getEvalOpsExperiment(experimentId));
        }
        return runtimeProxyClient.listEvalOpsExperimentItems(experimentId, variantId, page, pageSize);
    }

    @PostMapping("/api/runtime/evals/v2/experiments/{experimentId}/cancel")
    public ResponseEntity<Object> cancelEvalOpsExperiment(@PathVariable Long experimentId) {
        if (runtimeManagementAccess != null) {
            PlatformAuthenticatedSession session = requireRuntimePermission(PlatformPermissions.AGENT_EVALUATE);
            requireRuntimeResponseProject(
                    session,
                    PlatformPermissions.AGENT_EVALUATE,
                    runtimeProxyClient.getEvalOpsExperiment(experimentId));
        }
        return runtimeProxyClient.cancelEvalOpsExperiment(experimentId);
    }

    @GetMapping("/api/agents")
    public ResponseEntity<Object> listAgents(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String projectCode) {
        requireRuntimeProject(PlatformPermissions.AGENT_READ, projectId, projectCode);
        return runtimeProxyClient.listAgents(projectId, projectCode);
    }

    @GetMapping("/api/agents/statistics")
    public ResponseEntity<Object> getAgentStatistics(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String projectCode) {
        requireRuntimeProject(PlatformPermissions.AGENT_READ, projectId, projectCode);
        return runtimeProxyClient.getAgentStatistics(projectId, projectCode);
    }

    @PostMapping("/api/agents")
    public ResponseEntity<Object> createAgent(@RequestBody Map<String, Object> body) {
        PlatformAuthenticatedSession session = requireRuntimePermission(PlatformPermissions.AGENT_WRITE);
        requireRuntimeBodyProject(session, PlatformPermissions.AGENT_WRITE, body);
        return runtimeProxyClient.createAgent(body);
    }

    @GetMapping("/api/agents/{id}")
    public ResponseEntity<Object> getAgent(@PathVariable String id) {
        PlatformAuthenticatedSession session = requireRuntimePermission(PlatformPermissions.AGENT_READ);
        ResponseEntity<Object> response = runtimeProxyClient.getAgent(id);
        requireRuntimeResponseProject(session, PlatformPermissions.AGENT_READ, response);
        return response;
    }

    @PutMapping("/api/agents/{id}")
    public ResponseEntity<Object> updateAgent(@PathVariable String id,
                                              @RequestBody Map<String, Object> body) {
        if (runtimeManagementAccess != null) {
            PlatformAuthenticatedSession session = requireRuntimePermission(PlatformPermissions.AGENT_WRITE);
            ResponseEntity<Object> current = runtimeProxyClient.getAgent(id);
            requireRuntimeResponseProject(session, PlatformPermissions.AGENT_WRITE, current);
            if (!current.getStatusCode().is2xxSuccessful()) return current;
            if (body != null && (body.containsKey("projectId") || body.containsKey("projectCode"))) {
                requireRuntimeBodyProject(session, PlatformPermissions.AGENT_WRITE, body);
            }
        }
        guardProjectScopedSkillsOnAgentMove(id, body);
        return runtimeProxyClient.updateAgent(id, body);
    }

    private void guardProjectScopedSkillsOnAgentMove(String agentId, Map<String, Object> body) {
        if (agentSkillBindingSnapshotAssembler == null || body == null
                || (!body.containsKey("projectId") && !body.containsKey("projectCode"))) {
            return;
        }
        ResponseEntity<Object> currentResponse = runtimeProxyClient.getAgent(agentId);
        if (currentResponse == null || !currentResponse.getStatusCode().is2xxSuccessful()
                || !(currentResponse.getBody() instanceof Map<?, ?> current)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Agent project scope could not be checked before update");
        }
        boolean projectCodeChanged = body.containsKey("projectCode")
                && !Objects.equals(normalizedText(current.get("projectCode")),
                normalizedText(body.get("projectCode")));
        boolean projectIdChanged = body.containsKey("projectId")
                && !Objects.equals(normalizedLong(current.get("projectId")),
                normalizedLong(body.get("projectId")));
        boolean projectChanged = projectCodeChanged || projectIdChanged;
        if (!projectChanged) return;

        ResponseEntity<Object> configResponse = runtimeProxyClient.listAgentConfigVersions(agentId);
        if (configResponse == null || !configResponse.getStatusCode().is2xxSuccessful()
                || !(configResponse.getBody() instanceof Collection<?> versions)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Agent Skill scopes could not be checked before project migration");
        }
        if (hasProjectScopedSkill(versions)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Agent project cannot change while a saved config references a project-scoped Skill; "
                            + "remove an unpublished draft binding first, otherwise create a new Agent");
        }
    }

    private boolean hasProjectScopedSkill(Collection<?> versions) {
        for (Object version : versions) {
            if (!(version instanceof Map<?, ?> config)
                    || !(config.get("skills") instanceof Collection<?> skills)) {
                continue;
            }
            for (Object value : skills) {
                if (value instanceof Map<?, ?> skill
                        && "PROJECT".equalsIgnoreCase(normalizedText(skill.get("visibility")))) {
                    return true;
                }
            }
        }
        return false;
    }

    @DeleteMapping("/api/agents/{id}")
    public ResponseEntity<Object> deleteAgent(@PathVariable String id) {
        if (runtimeManagementAccess != null) {
            PlatformAuthenticatedSession session = requireRuntimePermission(PlatformPermissions.AGENT_WRITE);
            ResponseEntity<Object> current = runtimeProxyClient.getAgent(id);
            requireRuntimeResponseProject(session, PlatformPermissions.AGENT_WRITE, current);
            if (!current.getStatusCode().is2xxSuccessful()) return current;
        }
        return runtimeProxyClient.deleteAgent(id);
    }

    @GetMapping("/api/agents/{agentId}/config-versions")
    public ResponseEntity<Object> listAgentConfigVersions(@PathVariable String agentId) {
        if (runtimeManagementAccess != null) {
            PlatformAuthenticatedSession session = requireRuntimePermission(PlatformPermissions.AGENT_READ);
            requireRuntimeResponseProject(
                    session, PlatformPermissions.AGENT_READ, runtimeProxyClient.getAgent(agentId));
        }
        return runtimeProxyClient.listAgentConfigVersions(agentId);
    }

    @PutMapping("/api/agents/{agentId}/config-versions/draft")
    public ResponseEntity<Object> saveAgentConfigDraft(HttpServletRequest request,
                                                       @PathVariable String agentId,
                                                       @RequestBody Map<String, Object> body) {
        if (runtimeManagementAccess != null) {
            PlatformAuthenticatedSession session = requireRuntimePermission(PlatformPermissions.AGENT_WRITE);
            requireRuntimeResponseProject(
                    session, PlatformPermissions.AGENT_WRITE, runtimeProxyClient.getAgent(agentId));
        }
        Map<String, Object> runtimeBody;
        if (agentSkillBindingSnapshotAssembler == null) {
            runtimeBody = body;
        } else {
            String agentProjectCode = hasSkillField(body) ? resolveAgentProjectCode(agentId) : null;
            runtimeBody = agentSkillBindingSnapshotAssembler.assemble(request, body, agentProjectCode);
        }
        if (a2aRemoteBindingSnapshotAssembler != null) {
            runtimeBody = a2aRemoteBindingSnapshotAssembler.assemble(
                    request, runtimeBody, agentId);
        }
        return runtimeProxyClient.saveAgentConfigDraft(agentId, runtimeBody);
    }

    private boolean hasSkillField(Map<String, Object> body) {
        return body != null && body.containsKey("skills");
    }

    private String resolveAgentProjectCode(String agentId) {
        ResponseEntity<Object> response = runtimeProxyClient.getAgent(agentId);
        if (response == null || !response.getStatusCode().is2xxSuccessful()
                || !(response.getBody() instanceof Map<?, ?> agent)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Agent project scope could not be resolved before Skill binding");
        }
        Object value = agent.get("projectCode");
        return value instanceof String text && StringUtils.hasText(text) ? text.trim() : null;
    }

    @PostMapping("/api/agents/{agentId}/config-versions/{configVersionId}/publish")
    public ResponseEntity<Object> publishAgentConfigVersion(HttpServletRequest request,
                                                            @PathVariable String agentId,
                                                            @PathVariable Long configVersionId,
                                                            @RequestBody(required = false) Map<String, Object> body) {
        if (runtimeManagementAccess != null) {
            PlatformAuthenticatedSession session = requireRuntimePermission(PlatformPermissions.AGENT_PUBLISH);
            requireRuntimeResponseProject(
                    session, PlatformPermissions.AGENT_PUBLISH, runtimeProxyClient.getAgent(agentId));
        }
        revalidateAgentSkillBindingsForPublish(request, agentId, configVersionId);
        return runtimeProxyClient.publishAgentConfigVersion(
                agentId,
                configVersionId,
                body == null ? Map.of() : body);
    }

    private void revalidateAgentSkillBindingsForPublish(HttpServletRequest request,
                                                        String agentId,
                                                        Long configVersionId) {
        if (agentSkillBindingSnapshotAssembler == null
                && a2aRemoteBindingSnapshotAssembler == null) {
            return;
        }
        ResponseEntity<Object> response = runtimeProxyClient.listAgentConfigVersions(agentId);
        if (response == null || !response.getStatusCode().is2xxSuccessful()
                || !(response.getBody() instanceof Collection<?> versions)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Agent config could not be revalidated before publishing");
        }
        Map<String, Object> target = versions.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .filter(candidate -> sameId(candidate.get("id"), configVersionId))
                .findFirst()
                .map(this::stringKeyMap)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Agent config version not found: " + configVersionId));
        if (agentSkillBindingSnapshotAssembler != null && target.containsKey("skills")) {
            Object rawSkills = target.get("skills");
            if (!(rawSkills instanceof Collection<?> skills)) {
                throw new org.springframework.web.server.ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "Agent config returned an invalid Skill binding snapshot");
            }
            if (!skills.isEmpty()) {
                Map<String, Object> canonical = agentSkillBindingSnapshotAssembler.assemble(
                        request, target, resolveAgentProjectCode(agentId));
                requireAttestedSkillSnapshots(skills, canonical.get("skills"));
            }
        }
        revalidateA2aRemoteBindingsForPublish(request, agentId, target);
    }

    private void revalidateA2aRemoteBindingsForPublish(
            HttpServletRequest request,
            String agentId,
            Map<String, Object> target) {
        if (a2aRemoteBindingSnapshotAssembler == null || !target.containsKey("remoteAgents")) return;
        Object persistedValue = target.get("remoteAgents");
        if (!(persistedValue instanceof Collection<?> persisted)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Agent config returned an invalid A2A remote binding snapshot");
        }
        // The server-owned empty catalog has no remote snapshots to attest.
        // Ordinary Workflow-only publication must not require unrelated A2A management.
        if (persisted.isEmpty()) return;
        Map<String, Object> canonical = a2aRemoteBindingSnapshotAssembler.assemble(
                request, target, agentId);
        requireAttestedSnapshots(
                persisted, canonical.get("remoteAgents"), A2A_REMOTE_ATTESTED_FIELDS,
                "Agent A2A remote binding snapshot differs from the Control catalog; save the draft again before publishing");
    }

    private void requireAttestedSkillSnapshots(Collection<?> persisted, Object canonicalValue) {
        requireAttestedSnapshots(persisted, canonicalValue, AGENT_SKILL_ATTESTED_FIELDS,
                "Agent Skill binding snapshot differs from the Control catalog; save the draft again before publishing");
    }

    private void requireAttestedSnapshots(
            Collection<?> persisted,
            Object canonicalValue,
            List<String> fields,
            String conflictMessage) {
        if (!(canonicalValue instanceof Collection<?> canonical) || persisted.size() != canonical.size()) {
            throw snapshotConflict(conflictMessage);
        }
        var persistedIterator = persisted.iterator();
        var canonicalIterator = canonical.iterator();
        while (persistedIterator.hasNext()) {
            Object persistedValue = persistedIterator.next();
            Object canonicalEntry = canonicalIterator.next();
            if (!(persistedValue instanceof Map<?, ?> persistedMap)
                    || !(canonicalEntry instanceof Map<?, ?> canonicalMap)) {
                throw snapshotConflict(conflictMessage);
            }
            for (String field : fields) {
                if (!sameSnapshotValue(persistedMap.get(field), canonicalMap.get(field))) {
                    throw snapshotConflict(conflictMessage);
                }
            }
        }
    }

    private boolean sameSnapshotValue(Object persisted, Object canonical) {
        if (persisted instanceof Number persistedNumber && canonical instanceof Number canonicalNumber) {
            return new java.math.BigDecimal(persistedNumber.toString())
                    .compareTo(new java.math.BigDecimal(canonicalNumber.toString())) == 0;
        }
        return Objects.equals(persisted, canonical);
    }

    private org.springframework.web.server.ResponseStatusException snapshotConflict(String message) {
        return new org.springframework.web.server.ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private boolean sameId(Object value, Long expected) {
        if (expected == null) return false;
        if (value instanceof Number number) return number.longValue() == expected;
        try {
            return value != null && Long.parseLong(String.valueOf(value)) == expected;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private Map<String, Object> stringKeyMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private String normalizedText(Object value) {
        String text = value == null ? null : String.valueOf(value).trim();
        return StringUtils.hasText(text) ? text : null;
    }

    private Long normalizedLong(Object value) {
        if (value instanceof Number number) return number.longValue();
        try {
            return value == null || !StringUtils.hasText(String.valueOf(value))
                    ? null : Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    @PostMapping("/api/agents/{agentId}/config-versions/{configVersionId}/copy-to-draft")
    public ResponseEntity<Object> copyAgentConfigVersionToDraft(@PathVariable String agentId,
                                                                @PathVariable Long configVersionId) {
        if (runtimeManagementAccess != null) {
            PlatformAuthenticatedSession session = requireRuntimePermission(PlatformPermissions.AGENT_WRITE);
            requireRuntimeResponseProject(
                    session, PlatformPermissions.AGENT_WRITE, runtimeProxyClient.getAgent(agentId));
        }
        return runtimeProxyClient.copyAgentConfigVersionToDraft(agentId, configVersionId);
    }

    @PostMapping("/api/runtime/tools/{qualifiedName}/execute")
    public ResponseEntity<Object> executeRuntimeTool(@PathVariable String qualifiedName,
                                                     @RequestBody Map<String, Object> body) {
        return runtimeProxyClient.executeRuntimeTool(qualifiedName, body);
    }

    @PostMapping("/api/runtime/compositions/{qualifiedName}/execute")
    public ResponseEntity<Object> executeRuntimeComposition(@PathVariable String qualifiedName,
                                                            @RequestBody Map<String, Object> body) {
        return runtimeProxyClient.executeRuntimeComposition(qualifiedName, body);
    }

    @PostMapping("/api/runtime/interactions/{sessionId}/resume")
    public ResponseEntity<Object> resumeRuntimeInteraction(@PathVariable String sessionId,
                                                           @RequestBody Map<String, Object> body) {
        // Workflow wfi_ sessions must use authenticated Embed/Agent paths, not this compatibility proxy.
        if (sessionId != null && sessionId.trim().startsWith("wfi_")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "success", false,
                    "code", "RUNTIME_INTERACTION_FORBIDDEN",
                    "answer", "Workflow interactions cannot be resumed via the public compatibility endpoint",
                    "interactionId", sessionId.trim()));
        }
        return runtimeProxyClient.resumeRuntimeInteraction(sessionId, body);
    }

    @PostMapping("/api/runtime/debug-sessions")
    public ResponseEntity<Object> createRuntimeDebugSession(@RequestBody Map<String, Object> body) {
        return debugSessionGateway.create(body);
    }

    @GetMapping("/api/runtime/debug-sessions/{sessionId}")
    public ResponseEntity<Object> getRuntimeDebugSession(@PathVariable String sessionId) {
        return debugSessionGateway.get(sessionId);
    }

    @GetMapping("/api/runtime/debug-sessions/by-creation-key/{key}")
    public ResponseEntity<Object> getRuntimeDebugSessionByCreationKey(@PathVariable String key) {
        return debugSessionGateway.getByCreationKey(key);
    }

    @PostMapping("/api/runtime/debug-sessions/{sessionId}/submit")
    public ResponseEntity<Object> submitRuntimeDebugSession(@PathVariable String sessionId,
                                                            @RequestBody Map<String, Object> body) {
        return debugSessionGateway.submit(sessionId, body);
    }

    @PostMapping("/api/runtime/debug-sessions/{sessionId}/cancel")
    public ResponseEntity<Object> cancelRuntimeDebugSession(@PathVariable String sessionId) {
        return debugSessionGateway.cancel(sessionId);
    }

    @PostMapping(value = "/api/runtime/debug-sessions/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> createRuntimeDebugSessionStream(
            @RequestBody Map<String, Object> body) {
        return debugSessionGateway.streamCreate(body);
    }

    @PostMapping(value = "/api/runtime/debug-sessions/{sessionId}/submit/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> submitRuntimeDebugSessionStream(
            @PathVariable String sessionId,
            @RequestBody Map<String, Object> body) {
        return debugSessionGateway.streamSubmit(sessionId, body);
    }

    @GetMapping("/api/runtime/interactions/human-approvals")
    public ResponseEntity<Object> listHumanApprovals(@RequestParam(required = false) String agentId,
                                                     @RequestParam(required = false) String userId,
                                                     @RequestParam(defaultValue = "50") int limit) {
        if (StringUtils.hasText(agentId) && runtimeManagementAccess != null) {
            PlatformAuthenticatedSession session = requireRuntimePermission(PlatformPermissions.AGENT_DEBUG);
            requireRuntimeResponseProject(
                    session, PlatformPermissions.AGENT_DEBUG, runtimeProxyClient.getAgent(agentId));
        } else {
            requireRuntimeProject(PlatformPermissions.AGENT_DEBUG, null, null);
        }
        return runtimeProxyClient.listHumanApprovals(agentId, userId, limit);
    }

    private Map<String, Object> attestedEvalBody(HttpServletRequest request,
                                                 Map<String, Object> body) {
        Object candidate = request == null ? null
                : request.getAttribute(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE);
        if (!(candidate instanceof PlatformAuthenticatedSession session) || session.user() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Platform session is required for EvalOps");
        }
        Map<String, Object> safe = body == null ? new LinkedHashMap<>() : new LinkedHashMap<>(body);
        for (String field : List.of(
                "tenantId", "createdBy", "targetSnapshotId", "targetFingerprint",
                "evaluationPolicy", "evalContext", "__runtimeEvalExecutionContext")) {
            safe.remove(field);
        }
        Object variants = safe.get("variants");
        if (variants instanceof Collection<?> values) {
            List<Map<String, Object>> sanitized = new ArrayList<>();
            for (Object value : values) {
                if (!(value instanceof Map<?, ?> raw)) continue;
                Map<String, Object> variant = new LinkedHashMap<>();
                for (String field : List.of("key", "variantKey", "displayName", "role", "configVersionId")) {
                    if (raw.containsKey(field)) variant.put(field, raw.get(field));
                }
                sanitized.add(variant);
            }
            safe.put("variants", sanitized);
        }
        safe.put("tenantId", "default");
        PlatformPrincipal user = session.user();
        String username = StringUtils.hasText(user.getUsername()) ? user.getUsername().trim() : "unknown";
        safe.put("createdBy", "platform:" + user.getId() + ":" + username);
        return safe;
    }

    private static List<Map<String, Object>> toGatewayAgentItems(Object runtimeBody) {
        List<Map<String, Object>> agents = new ArrayList<>();
        for (Object value : extractItems(runtimeBody)) {
            if (!(value instanceof Map<?, ?> source)) {
                continue;
            }
            if (!isGatewayAgentEnabled(source) || !isCatalogVisible(source.get("visibility"))) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", source.get("id"));
            item.put("keySlug", source.get("keySlug"));
            item.put("name", source.get("name"));
            item.put("projectCode", source.get("projectCode"));
            item.put("visibility", source.get("visibility"));
            agents.add(item);
        }
        return agents;
    }

    private static Collection<?> extractItems(Object body) {
        if (body instanceof Collection<?> items) {
            return items;
        }
        if (body instanceof Map<?, ?> map) {
            for (String key : List.of("data", "records", "items", "agents")) {
                Object value = map.get(key);
                if (value instanceof Collection<?> items) {
                    return items;
                }
            }
        }
        return List.of();
    }

    private static boolean isGatewayAgentEnabled(Map<?, ?> agent) {
        Object enabled = agent.get("enabled");
        return enabled == null || Boolean.TRUE.equals(enabled)
                || "true".equalsIgnoreCase(String.valueOf(enabled));
    }

    private static boolean isCatalogVisible(Object visibility) {
        if (visibility == null || String.valueOf(visibility).isBlank()) {
            return true;
        }
        return !"PRIVATE".equalsIgnoreCase(String.valueOf(visibility).trim());
    }

    /**
     * Prefer the server-attested Cookie or Bearer login via the internal Runtime contract.
     * Without a platform login, forward to public Runtime execute with userTrusted=false
     * (body userId is business data only and never becomes ACL identity).
     */
    private ResponseEntity<Map<String, Object>> executeAgentWithServerIdentity(
            String authorization,
            Map<String, Object> body,
            boolean detailed) {
        Map<String, Object> safeBody = body == null ? new LinkedHashMap<>() : new LinkedHashMap<>(body);
        // Never accept client-injected trust envelopes on the public surface.
        safeBody.remove("__workflowExecutionIdentity");
        safeBody.remove("trustedIdentity");
        safeBody.remove("_trustedUserId");

        Optional<PlatformPrincipal> user = authenticatedAgentUser(authorization);
        if (user.isPresent() && trustedExecutionGateway != null) {
            String trustedUserId = trustedAgentUserId(user.get());
            ResponseEntity<Map<String, Object>> trusted =
                    trustedExecutionGateway.executeTrusted(safeBody, "AGENT", trustedUserId);
            if (!detailed) {
                return trusted;
            }
            // Detailed public route historically hit /execute/detailed; when identity is trusted,
            // reuse the same result envelope (steps may be absent — acceptable fail-closed tradeoff).
            return trusted;
        }
        if (detailed) {
            return runtimeProxyClient.executeAgentDetailed(safeBody);
        }
        return runtimeProxyClient.executeAgent(safeBody);
    }

    private ResponseEntity<StreamingResponseBody> executeAgentStreamWithServerIdentity(
            String authorization,
            Map<String, Object> body) {
        if (runtimeAgentStreamProxy == null) {
            throw new IllegalStateException("Runtime stream proxy is not configured");
        }
        Map<String, Object> safeBody = body == null ? new LinkedHashMap<>() : new LinkedHashMap<>(body);
        safeBody.remove("__workflowExecutionIdentity");
        safeBody.remove("trustedIdentity");
        safeBody.remove("_trustedUserId");
        Optional<PlatformPrincipal> user = authenticatedAgentUser(authorization);
        StreamingResponseBody stream;
        if (user.isPresent() && trustedExecutionGateway != null) {
            String trustedUserId = trustedAgentUserId(user.get());
            stream = outputStream -> trustedExecutionGateway.streamTrusted(
                    safeBody, "AGENT", trustedUserId, outputStream, SseStreamRelay.passthrough());
        } else {
            stream = outputStream -> runtimeAgentStreamProxy.stream(safeBody, outputStream);
        }
        return streamResponse(stream);
    }

    private Optional<PlatformPrincipal> authenticatedAgentUser(String authorization) {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            Object candidate = attributes.getRequest()
                    .getAttribute(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE);
            return candidate instanceof PlatformAuthenticatedSession session
                    ? Optional.ofNullable(session.user()) : Optional.empty();
        }
        // Direct non-MVC callers retain the existing Bearer resolution contract.
        return bearerAuthService == null ? Optional.empty()
                : bearerAuthService.resolveBearerPrincipal(authorization);
    }

    private String trustedAgentUserId(PlatformPrincipal user) {
        if (personalMemoryIdentityResolver == null) {
            // Compatibility path for narrow unit tests and legacy direct construction only.
            return String.valueOf(user.getId());
        }
        return personalMemoryIdentityResolver
                .resolveAttestedPlatformPrincipal(user, null, "default")
                .runtimeUserId();
    }

    private ResponseEntity<StreamingResponseBody> streamResponse(StreamingResponseBody stream) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noCache().noTransform())
                .header("X-Accel-Buffering", "no")
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(stream);
    }
}
