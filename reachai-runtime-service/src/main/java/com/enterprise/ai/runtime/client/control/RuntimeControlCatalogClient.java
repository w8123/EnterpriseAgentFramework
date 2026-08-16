package com.enterprise.ai.runtime.client.control;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;

@FeignClient(name = "reachai-control-service", url = "${services.control-service.url:http://localhost:18603}")
public interface RuntimeControlCatalogClient {

    @GetMapping("/internal/control/page-actions/lookup")
    PageActionCatalogEntry getPageAction(@RequestParam("projectCode") String projectCode,
                                         @RequestParam("pageKey") String pageKey,
                                         @RequestParam("actionKey") String actionKey);

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
                                      int executionTimeoutMs) {
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
