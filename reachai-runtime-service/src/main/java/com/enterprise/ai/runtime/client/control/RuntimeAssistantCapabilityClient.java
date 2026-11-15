package com.enterprise.ai.runtime.client.control;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;
import java.util.Map;

/**
 * Control owns what a business system publishes to employees, who may call it, write approval and
 * audit. Runtime only asks, naming the employee it verified for the chat session.
 */
@FeignClient(
        name = "reachai-control-assistant-capability",
        url = "${services.control-service.url:http://localhost:18603}",
        configuration = RuntimeAgentSkillInternalAuthFeignConfig.class)
public interface RuntimeAssistantCapabilityClient {

    @PostMapping("/internal/control/assistant-capabilities/search")
    SearchResult search(@RequestBody SearchRequest request);

    @PostMapping("/internal/control/assistant-capabilities/call")
    CallResult call(@RequestBody CallRequest request);

    /** Every read-only capability the account may call, for generating evaluation questions. */
    @PostMapping("/internal/control/assistant-capabilities/read-catalog")
    SearchResult readCatalog(@RequestBody SearchRequest request);

    /**
     * {@code readOnly=true} (automatic evaluation): Control shows and runs only READ capabilities,
     * whatever the account could otherwise do.
     */
    record SearchRequest(String tenantId, String projectCode, String externalUserId, String query, Integer limit,
                         Boolean readOnly) {
        public SearchRequest(String tenantId, String projectCode, String externalUserId, String query, Integer limit) {
            this(tenantId, projectCode, externalUserId, query, limit, null);
        }
    }

    record CallRequest(String tenantId, String projectCode, String externalUserId, String toolName,
                       Map<String, Object> arguments, Boolean readOnly) {
        public CallRequest(String tenantId, String projectCode, String externalUserId, String toolName,
                           Map<String, Object> arguments) {
            this(tenantId, projectCode, externalUserId, toolName, arguments, null);
        }
    }

    record SearchResult(boolean available, String message, int total, List<Map<String, Object>> tools) {
    }

    record CallResult(boolean success, boolean approvalPending, Map<String, Object> output,
                      String errorCode, String message, String runId, String traceId) {
    }
}
