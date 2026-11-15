package com.enterprise.ai.runtime.client.control;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;

@FeignClient(name = "reachai-control-service", url = "${services.control-service.url:http://localhost:18603}",
        configuration = RuntimeAgentSkillInternalAuthFeignConfig.class)
public interface RuntimeControlCatalogClient {

    @GetMapping("/internal/control/page-actions/lookup")
    PageActionCatalogEntry getPageAction(@RequestParam("projectCode") String projectCode,
                                         @RequestParam("pageKey") String pageKey,
                                         @RequestParam("actionKey") String actionKey);

    @PostMapping("/internal/control/page-action-effects/resolve")
    TrustedPageEffectApproval resolvePageEffect(@RequestBody TrustedPageEffectRequest request);

    record TrustedPageEffectRequest(String projectCode, String pageKey, String actionKey) { }

    record TrustedPageEffectApproval(Long approvalId, long revision, Long pageId, Long actionId,
                                     String projectCode, String pageKey, String actionKey,
                                     String effect, String catalogHash, String implementationVersion,
                                     String targetAlias, String actionTitle, String actionDescription,
                                     String routePattern, Object inputSchema, Map<String, String> fieldLabels, boolean active) { }

    @GetMapping("/internal/control/page-actions")
    List<PageActionCatalogEntry> listPageActions(
            @RequestParam("projectCode") String projectCode,
            @RequestParam("pageKey") String pageKey,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam("limit") int limit);

    @PostMapping("/internal/control/page-bridge/execute")
    PageBridgeExecutionResponse executePageBridge(@RequestBody PageBridgeExecutionRequest request);

    @PostMapping("/internal/control/page-bridge/resolve-context")
    PageBridgeContextResolution resolvePageBridgeContext(@RequestBody PageBridgeContextResolutionRequest request);

    /** Whose business identity a scheduled Automation acts as; re-asked before every run. */
    @PostMapping("/internal/control/automation-run-as/resolve")
    AutomationRunAsResolution resolveAutomationRunAs(@RequestBody AutomationRunAsRequest request);

    /** 「用员工自己的令牌调用」: what to call a business system with for this employee. Never log the answer. */
    @PostMapping("/internal/control/employee-business-tokens/resolve")
    EmployeeBusinessTokenResolution resolveEmployeeBusinessToken(
            @RequestBody EmployeeBusinessTokenSubject subject);

    @PostMapping("/internal/control/employee-business-tokens/expired")
    Map<String, Object> employeeBusinessTokenExpired(@RequestBody EmployeeBusinessTokenSubject subject);

    record EmployeeBusinessTokenSubject(String tenantId, String projectCode, String externalUserId) {
    }

    record EmployeeBusinessTokenResolution(String mode, List<String> origins, String token) {
        @Override
        public String toString() {
            return "EmployeeBusinessTokenResolution[mode=" + mode + ", origins=" + origins
                    + ", token=" + (token == null ? "none" : "***") + "]";
        }
    }

    record AutomationRunAsRequest(String tenantId, String actorId, Long projectId, String projectCode) {
    }

    record AutomationRunAsResolution(String code, String tenantId, String externalUserId, List<String> roles) {
        public boolean resolved() {
            return "RESOLVED".equals(code);
        }
    }

    record PageActionCatalogEntry(
            Long id,
            String projectCode,
            String pageKey,
            String actionKey,
            String title,
            String description,
            String riskLevel,
            boolean confirmRequired,
            String permissionKey,
            Object inputSchema,
            Object outputSchema,
            Object sampleArgs,
            List<String> allowedAgentIds,
            String implementationRef,
            Object metadata,
            String status
    ) {
        public PageActionCatalogEntry(
                String projectCode,
                String pageKey,
                String actionKey,
                String status) {
            this(
                    null,
                    projectCode,
                    pageKey,
                    actionKey,
                    null,
                    null,
                    null,
                    false,
                    null,
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    List.of(),
                    null,
                    Map.of(),
                    status);
        }
    }

    record PageBridgeExecutionRequest(String sessionId,
                                      String projectCode,
                                      String agentId,
                                      String currentPageKey,
                                      String targetPageKey,
                                      String targetRoute,
                                      String actionKey,
                                      Map<String, Object> args,
                                      boolean confirmRequired,
                                      int confirmationTimeoutMs,
                                      int executionTimeoutMs,
                                      String identitySource,
                                      String identityTenantId,
                                      String identityUserId,
                                      String supervisorTraceId,
                                      String resumeNavigationRequestId,
                                      String navigationInteractionId,
                                      Long effectApprovalId,
                                      Long effectApprovalRevision,
                                      String effectCatalogHash) {
        public PageBridgeExecutionRequest(String sessionId, String projectCode, String agentId,
                                          String currentPageKey, String targetPageKey, String targetRoute,
                                          String actionKey, Map<String, Object> args, boolean confirmRequired,
                                          int confirmationTimeoutMs, int executionTimeoutMs, String identitySource,
                                          String identityTenantId, String identityUserId, String supervisorTraceId,
                                          String resumeNavigationRequestId, String navigationInteractionId) {
            this(sessionId, projectCode, agentId, currentPageKey, targetPageKey, targetRoute, actionKey, args,
                    confirmRequired, confirmationTimeoutMs, executionTimeoutMs, identitySource, identityTenantId,
                    identityUserId, supervisorTraceId, resumeNavigationRequestId, navigationInteractionId, null, null, null);
        }
        public PageBridgeExecutionRequest(String sessionId, String projectCode, String agentId,
                                          String currentPageKey, String targetPageKey, String targetRoute,
                                          String actionKey, Map<String, Object> args, boolean confirmRequired,
                                          int confirmationTimeoutMs, int executionTimeoutMs, String identitySource,
                                          String identityTenantId, String identityUserId, String supervisorTraceId,
                                          String resumeNavigationRequestId) {
            this(sessionId, projectCode, agentId, currentPageKey, targetPageKey, targetRoute, actionKey,
                    args, confirmRequired, confirmationTimeoutMs, executionTimeoutMs,
                    identitySource, identityTenantId, identityUserId, supervisorTraceId, resumeNavigationRequestId, null);
        }
        public PageBridgeExecutionRequest(String sessionId, String projectCode, String agentId,
                                          String currentPageKey, String targetPageKey, String targetRoute,
                                          String actionKey, Map<String, Object> args, boolean confirmRequired,
                                          int confirmationTimeoutMs, int executionTimeoutMs, String identitySource,
                                          String identityTenantId, String identityUserId, String supervisorTraceId) {
            this(sessionId, projectCode, agentId, currentPageKey, targetPageKey, targetRoute, actionKey,
                    args, confirmRequired, confirmationTimeoutMs, executionTimeoutMs,
                    identitySource, identityTenantId, identityUserId, supervisorTraceId, null, null);
        }
        public PageBridgeExecutionRequest(String sessionId, String projectCode, String agentId,
                                          String currentPageKey, String targetPageKey, String targetRoute,
                                          String actionKey, Map<String, Object> args, boolean confirmRequired,
                                          int confirmationTimeoutMs, int executionTimeoutMs, String identitySource,
                                          String identityTenantId, String identityUserId) {
            this(sessionId, projectCode, agentId, currentPageKey, targetPageKey, targetRoute, actionKey,
                    args, confirmRequired, confirmationTimeoutMs, executionTimeoutMs,
                    identitySource, identityTenantId, identityUserId, null, null, null);
        }
    }

    record PageBridgeExecutionResponse(boolean success,
                                       String code,
                                       String status,
                                       Object data,
                                       List<Map<String, Object>> phases) {
    }

    record PageBridgeContextResolutionRequest(String sessionId, String projectCode) {
    }

    record PageBridgeContextResolution(boolean resolved,
                                       String code,
                                       String message,
                                       String sessionId,
                                       String projectCode,
                                       String agentId,
                                       String currentPageKey,
                                       String pageInstanceId,
                                       String route) {
    }
}
