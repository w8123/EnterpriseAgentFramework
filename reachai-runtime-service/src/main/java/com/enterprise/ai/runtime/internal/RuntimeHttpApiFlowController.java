package com.enterprise.ai.runtime.internal;

import com.enterprise.ai.runtime.internal.RuntimeHttpApiFlowService.BatchRequest;
import com.enterprise.ai.runtime.internal.RuntimeHttpApiFlowService.FlowResult;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** HTTP API → single-call Workflow, in batches, so accepted APIs can be given to employees and the assistant. */
@RestController
@RequiredArgsConstructor
public class RuntimeHttpApiFlowController {

    private final RuntimeHttpApiFlowService service;
    private final RuntimeHttpApiFlowNamingService naming;
    private final com.enterprise.ai.runtime.workflow.RuntimeWorkflowHttpApiService httpApis;

    /** After a credential was replaced: published read-only flows of the system move to it, writes do not. */
    @PostMapping("/api/workflows/http-api-flows/credential-refresh")
    public ResponseEntity<Object> refreshCredential(@RequestBody Map<String, Object> body) {
        Long projectId = body.get("projectId") instanceof Number number ? number.longValue() : null;
        String projectCode = body.get("projectCode") == null ? null : String.valueOf(body.get("projectCode"));
        return ResponseEntity.ok(httpApis.refreshReadOnlyCredentialPins(projectId, projectCode));
    }

    @PostMapping("/api/workflows/http-api-flows")
    public ResponseEntity<List<FlowResult>> generate(@RequestBody BatchRequest request) {
        return ResponseEntity.ok(service.generate(request));
    }

    /** Chinese names suggested by the system's assistant model; nothing is created. */
    @PostMapping("/api/workflows/http-api-flows/names")
    public ResponseEntity<Map<Long, String>> names(@RequestBody RuntimeHttpApiFlowNamingService.NamingRequest request) {
        return ResponseEntity.ok(naming.suggest(request));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> invalid(IllegalArgumentException invalid) {
        return ResponseEntity.badRequest().body(Map.of(
                "code", "HTTP_API_FLOW_INVALID",
                "message", invalid.getMessage() == null ? "请求无效" : invalid.getMessage()));
    }
}
