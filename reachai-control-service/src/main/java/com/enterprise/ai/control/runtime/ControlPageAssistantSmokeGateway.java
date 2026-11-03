package com.enterprise.ai.control.runtime;

import org.springframework.http.ResponseEntity;

import java.util.Map;

/**
 * Port for the business-page smoke test: the implementation verifies the page token
 * (owned by the platform module) and signs only the resulting identity to Runtime.
 */
public interface ControlPageAssistantSmokeGateway {
    String RUNTIME_PATH = "/internal/runtime/workflows/ai-coding/page-assistant/smoke-test";

    ResponseEntity<Object> execute(String workflowId, String expectedProject, String rawToken,
                                   Map<String, Object> request);
}
