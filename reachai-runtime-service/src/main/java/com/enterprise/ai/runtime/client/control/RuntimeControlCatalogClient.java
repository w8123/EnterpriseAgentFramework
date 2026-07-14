package com.enterprise.ai.runtime.client.control;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;
import java.util.Map;

@FeignClient(name = "reachai-control-service", url = "${services.control-service.url:http://localhost:18603}")
public interface RuntimeControlCatalogClient {

    @GetMapping("/internal/control/page-actions/{projectCode}/{pageKey}/{actionKey}")
    PageActionCatalogEntry getPageAction(@PathVariable("projectCode") String projectCode,
                                         @PathVariable("pageKey") String pageKey,
                                         @PathVariable("actionKey") String actionKey);

    @PostMapping("/internal/control/page-bridge/execute")
    PageBridgeExecutionResponse executePageBridge(@RequestBody PageBridgeExecutionRequest request);

    record PageActionCatalogEntry(
            String projectCode,
            String pageKey,
            String actionKey,
            String status
    ) {
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
                                      int timeoutMs) {
    }

    record PageBridgeExecutionResponse(boolean success,
                                       String code,
                                       String status,
                                       Object data,
                                       List<Map<String, Object>> phases) {
    }
}
