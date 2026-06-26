package com.enterprise.ai.agent.platform.control.controller;

import com.enterprise.ai.agent.platform.control.context.*;
import com.enterprise.ai.common.dto.ApiResult;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/context")
@RequiredArgsConstructor
public class ContextController {

    private final ContextNamespaceService namespaceService;
    private final ContextItemService itemService;
    private final ContextBindingService bindingService;
    private final ContextEvidenceService evidenceService;
    private final ContextRetrievalService retrievalService;
    private final ContextComposerService composerService;
    private final ContextAuditService auditService;
    private final ContextLifecycleService lifecycleService;
    private final ContextOpsSummaryService opsSummaryService;

    @PostMapping("/namespaces")
    public ApiResult<ContextNamespaceResponse> createOrGetNamespace(@RequestBody ContextNamespaceRequest request) {
        try {
            return ApiResult.ok(namespaceService.createOrGetNamespace(request));
        } catch (IllegalArgumentException ex) {
            return ApiResult.fail(400, ex.getMessage());
        }
    }

    @GetMapping("/namespaces/{id}")
    public ApiResult<ContextNamespaceResponse> getNamespace(@PathVariable Long id) {
        try {
            return ApiResult.ok(namespaceService.getNamespace(id));
        } catch (IllegalArgumentException ex) {
            return ApiResult.fail(404, ex.getMessage());
        }
    }

    @GetMapping("/namespaces")
    public ApiResult<List<ContextNamespaceResponse>> listNamespaces(
            @RequestParam String tenantId,
            @RequestParam(required = false) String projectCode,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String namespaceType,
            @RequestParam(required = false) String status) {
        return ApiResult.ok(namespaceService.listNamespaces(tenantId, projectCode, projectId, namespaceType, status));
    }

    @DeleteMapping("/namespaces/{id}")
    public ApiResult<ContextNamespaceResponse> deleteNamespace(@PathVariable Long id) {
        try {
            return ApiResult.ok(namespaceService.markDeleted(id));
        } catch (IllegalArgumentException ex) {
            return ApiResult.fail(404, ex.getMessage());
        }
    }

    @PostMapping("/items")
    public ApiResult<ContextItemResponse> createItem(@RequestBody ContextItemCreateRequest request) {
        try {
            return ApiResult.ok(itemService.createItem(request));
        } catch (IllegalArgumentException ex) {
            return ApiResult.fail(400, ex.getMessage());
        }
    }

    @GetMapping("/items")
    public ApiResult<List<ContextItemResponse>> listItems(
            @RequestParam String tenantId,
            @RequestParam String memoryLane,
            @RequestParam(required = false) String projectCode,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) Long namespaceId,
            @RequestParam(required = false) String itemType,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        try {
            ContextItemListRequest request = new ContextItemListRequest();
            request.setTenantId(tenantId);
            request.setMemoryLane(memoryLane);
            request.setProjectCode(projectCode);
            request.setProjectId(projectId);
            request.setNamespaceId(namespaceId);
            request.setItemType(itemType);
            request.setStatus(status);
            request.setKeyword(keyword);
            request.setLimit(limit);
            request.setOffset(offset);
            return ApiResult.ok(itemService.listItems(request));
        } catch (IllegalArgumentException ex) {
            return ApiResult.fail(scopeErrorCode(ex), ex.getMessage());
        }
    }

    @GetMapping("/items/{id}")
    public ApiResult<ContextItemResponse> getItem(@PathVariable Long id, ContextQueryRequest scope) {
        try {
            return ApiResult.ok(itemService.getItem(id, scope));
        } catch (IllegalArgumentException ex) {
            return ApiResult.fail(scopeErrorCode(ex), ex.getMessage());
        }
    }

    @PutMapping("/items/{id}")
    public ApiResult<ContextItemResponse> updateItem(@PathVariable Long id,
                                                       @RequestBody ContextItemUpdateRequest request,
                                                       ContextQueryRequest scope) {
        try {
            return ApiResult.ok(itemService.updateItem(id, request, scope));
        } catch (IllegalArgumentException ex) {
            return ApiResult.fail(scopeErrorCode(ex), ex.getMessage());
        }
    }

    @PostMapping("/items/{id}/revoke")
    public ApiResult<ContextItemResponse> revokeItem(@PathVariable Long id, @RequestBody ContextQueryRequest scope) {
        try {
            return ApiResult.ok(itemService.revokeItem(id, scope));
        } catch (IllegalArgumentException ex) {
            return ApiResult.fail(scopeErrorCode(ex), ex.getMessage());
        }
    }

    @PostMapping("/items/{id}/stale")
    public ApiResult<ContextItemResponse> markStale(@PathVariable Long id, @RequestBody ContextQueryRequest scope) {
        try {
            return ApiResult.ok(itemService.markStale(id, scope));
        } catch (IllegalArgumentException ex) {
            return ApiResult.fail(scopeErrorCode(ex), ex.getMessage());
        }
    }

    @PostMapping("/items/{id}/verify")
    public ApiResult<ContextItemResponse> verifyItem(@PathVariable Long id,
                                                       @RequestBody Map<String, Object> body) {
        try {
            BigDecimal confidence = body.get("confidence") instanceof Number number
                    ? BigDecimal.valueOf(number.doubleValue()) : null;
            String trustLevel = body.get("trustLevel") == null ? null : String.valueOf(body.get("trustLevel"));
            ContextQueryRequest scope = scopeFromBody(body);
            return ApiResult.ok(itemService.verifyItem(id, confidence, trustLevel, scope));
        } catch (IllegalArgumentException ex) {
            return ApiResult.fail(scopeErrorCode(ex), ex.getMessage());
        }
    }

    @DeleteMapping("/items/{id}")
    public ApiResult<ContextItemResponse> deleteItem(@PathVariable Long id, @RequestBody ContextQueryRequest scope) {
        try {
            return ApiResult.ok(itemService.deleteItem(id, scope));
        } catch (IllegalArgumentException ex) {
            return ApiResult.fail(scopeErrorCode(ex), ex.getMessage());
        }
    }

    @PostMapping("/items/{id}/bindings")
    public ApiResult<ContextBindingResponse> bindItem(@PathVariable Long id,
                                                        @RequestBody ContextBindingRequest request,
                                                        ContextQueryRequest scope) {
        try {
            return ApiResult.ok(bindingService.bindItem(id, request, scope));
        } catch (IllegalArgumentException ex) {
            return ApiResult.fail(scopeErrorCode(ex), ex.getMessage());
        }
    }

    @GetMapping("/items/{id}/bindings")
    public ApiResult<List<ContextBindingResponse>> listBindings(@PathVariable Long id, ContextQueryRequest scope) {
        try {
            return ApiResult.ok(bindingService.listBindingsForItem(id, scope));
        } catch (IllegalArgumentException ex) {
            return ApiResult.fail(scopeErrorCode(ex), ex.getMessage());
        }
    }

    @DeleteMapping("/items/{itemId}/bindings/{bindingId}")
    public ApiResult<Void> unbindItem(@PathVariable Long itemId,
                                        @PathVariable Long bindingId,
                                        ContextQueryRequest scope) {
        try {
            bindingService.unbindItem(bindingId, scope);
            return ApiResult.ok();
        } catch (IllegalArgumentException ex) {
            return ApiResult.fail(scopeErrorCode(ex), ex.getMessage());
        }
    }

    @PostMapping("/items/{id}/evidence")
    public ApiResult<ContextEvidenceResponse> addEvidence(@PathVariable Long id,
                                                            @RequestBody ContextEvidenceRequest request,
                                                            ContextQueryRequest scope) {
        try {
            return ApiResult.ok(evidenceService.addEvidence(id, request, scope));
        } catch (IllegalArgumentException ex) {
            return ApiResult.fail(scopeErrorCode(ex), ex.getMessage());
        }
    }

    @GetMapping("/items/{id}/evidence")
    public ApiResult<List<ContextEvidenceResponse>> listEvidence(@PathVariable Long id, ContextQueryRequest scope) {
        try {
            return ApiResult.ok(evidenceService.listEvidence(id, scope));
        } catch (IllegalArgumentException ex) {
            return ApiResult.fail(scopeErrorCode(ex), ex.getMessage());
        }
    }

    @PostMapping("/query")
    public ApiResult<List<ContextSearchResult>> query(@RequestBody ContextQueryRequest request) {
        try {
            return ApiResult.ok(retrievalService.search(request));
        } catch (IllegalArgumentException ex) {
            return ApiResult.fail(400, ex.getMessage());
        }
    }

    @PostMapping("/package")
    public ApiResult<ContextPackageResponse> composePackage(@RequestBody ContextPackageComposeRequest request) {
        try {
            return ApiResult.ok(composerService.compose(request));
        } catch (IllegalArgumentException ex) {
            return ApiResult.fail(400, ex.getMessage());
        }
    }

    @GetMapping("/audit")
    public ApiResult<List<ContextAuditEventResponse>> listAudit(
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false) String projectCode,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) Long itemId,
            @RequestParam(required = false) Long namespaceId,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String actorType,
            @RequestParam(required = false) String actorId,
            @RequestParam(required = false) String decision,
            @RequestParam(required = false) String traceId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            LocalDateTime dateFrom,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            LocalDateTime dateTo,
            @RequestParam(defaultValue = "50") int limit) {
        ContextAuditListRequest request = new ContextAuditListRequest();
        request.setTenantId(tenantId);
        request.setProjectCode(projectCode);
        request.setProjectId(projectId);
        request.setItemId(itemId);
        request.setNamespaceId(namespaceId);
        request.setEventType(eventType);
        request.setActorType(actorType);
        request.setActorId(actorId);
        request.setDecision(decision);
        request.setTraceId(traceId);
        request.setDateFrom(dateFrom);
        request.setDateTo(dateTo);
        request.setLimit(limit);
        return ApiResult.ok(auditService.listAuditEvents(request));
    }

    @GetMapping("/ops/summary")
    public ApiResult<ContextOpsSummaryResponse> opsSummary(
            @RequestParam String tenantId,
            @RequestParam(required = false) String projectCode,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String memoryLane,
            @RequestParam(defaultValue = "false") boolean includeRuntimeUser) {
        try {
            return ApiResult.ok(opsSummaryService.summarize(
                    tenantId, projectCode, projectId, memoryLane, includeRuntimeUser));
        } catch (IllegalArgumentException ex) {
            return ApiResult.fail(scopeErrorCode(ex), ex.getMessage());
        }
    }

    @PostMapping("/lifecycle/run")
    public ApiResult<ContextLifecycleRunResponse> runLifecycle(@RequestBody ContextLifecycleRunRequest request) {
        try {
            return ApiResult.ok(lifecycleService.run(request));
        } catch (IllegalArgumentException ex) {
            return ApiResult.fail(scopeErrorCode(ex), ex.getMessage());
        }
    }

    private ContextQueryRequest scopeFromBody(Map<String, Object> body) {
        ContextQueryRequest scope = new ContextQueryRequest();
        if (body.get("tenantId") != null) {
            scope.setTenantId(String.valueOf(body.get("tenantId")));
        }
        if (body.get("projectCode") != null) {
            scope.setProjectCode(String.valueOf(body.get("projectCode")));
        }
        if (body.get("projectId") instanceof Number projectId) {
            scope.setProjectId(projectId.longValue());
        }
        if (body.get("memoryLane") != null) {
            scope.setMemoryLane(String.valueOf(body.get("memoryLane")));
        }
        if (body.get("userId") != null) {
            scope.setUserId(String.valueOf(body.get("userId")));
        }
        if (body.get("agentId") != null) {
            scope.setAgentId(String.valueOf(body.get("agentId")));
        }
        if (body.get("workflowId") != null) {
            scope.setWorkflowId(String.valueOf(body.get("workflowId")));
        }
        if (body.get("pageInstanceId") != null) {
            scope.setPageInstanceId(String.valueOf(body.get("pageInstanceId")));
        }
        if (body.get("sessionId") != null) {
            scope.setSessionId(String.valueOf(body.get("sessionId")));
        }
        return scope;
    }

    private int scopeErrorCode(IllegalArgumentException ex) {
        String message = ex.getMessage();
        if (!StringUtils.hasText(message)) {
            return 400;
        }
        if (message.contains("required")
                || message.contains("Invalid memoryLane")
                || message.contains("scope is required")) {
            return 400;
        }
        if (message.contains("access denied") || message.contains("No project access")) {
            return 403;
        }
        if (message.contains("not found")) {
            return 404;
        }
        return 400;
    }
}
