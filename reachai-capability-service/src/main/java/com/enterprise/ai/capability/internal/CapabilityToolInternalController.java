package com.enterprise.ai.capability.internal;

import lombok.RequiredArgsConstructor;
import com.enterprise.ai.common.capability.CapabilityInvocationRequest;
import com.enterprise.ai.common.capability.CapabilityInvocationResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class CapabilityToolInternalController {

    private final CapabilityToolLookupService lookupService;
    private final CapabilityToolExecutionService executionService;
    private final CapabilityInvocationApplicationService invocationService;

    @GetMapping("/internal/capability/tools/{qualifiedName}")
    public ResponseEntity<Map<String, Object>> getToolDefinition(@PathVariable("qualifiedName") String qualifiedName) {
        try {
            return ResponseEntity.ok(lookupService.getToolDefinition(qualifiedName));
        } catch (IllegalArgumentException ex) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("service", "reachai-capability-service");
            body.put("code", "CAPABILITY_TOOL_NOT_FOUND");
            body.put("qualifiedName", qualifiedName);
            body.put("message", ex.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
        }
    }

    @PostMapping("/internal/capability/tools/{qualifiedName}/execute")
    public ResponseEntity<Map<String, Object>> executeTool(@PathVariable("qualifiedName") String qualifiedName,
                                                           @RequestBody(required = false) Map<String, Object> request) {
        Map<String, Object> body = request == null ? Map.of() : request;
        if (isTypedInvocation(body)) {
            try {
                CapabilityInvocationRequest typedRequest = CapabilityInvocationRequest.fromRuntime(qualifiedName, body);
                CapabilityInvocationResponse response = invocationService.invoke(typedRequest);
                return ResponseEntity.ok(response.toLegacyMap());
            } catch (IllegalArgumentException ex) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(error("CAPABILITY_INVOCATION_CONTRACT_INVALID", qualifiedName, ex.getMessage()));
            }
        }
        try {
            return ResponseEntity.ok(executionService.execute(qualifiedName, body));
        } catch (IllegalArgumentException ex) {
            Map<String, Object> errorBody = error("CAPABILITY_TOOL_NOT_FOUND", qualifiedName, ex.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorBody);
        } catch (IllegalStateException ex) {
            Map<String, Object> errorBody = error(
                    "CAPABILITY_TOOL_EXECUTION_REJECTED", qualifiedName, ex.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorBody);
        }
    }

    /** Canonical Runtime -> Capability Contract V1 endpoint. */
    @PostMapping("/internal/capability/invocations")
    public ResponseEntity<?> invoke(@RequestBody(required = false) Map<String, Object> request) {
        Map<String, Object> body = request == null ? Map.of() : request;
        try {
            CapabilityInvocationRequest typedRequest = CapabilityInvocationRequest.fromWire(body);
            return ResponseEntity.ok(invocationService.invoke(typedRequest));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(error("CAPABILITY_INVOCATION_CONTRACT_INVALID", null, ex.getMessage()));
        }
    }

    private boolean isTypedInvocation(Map<String, Object> request) {
        Object version = request.get("contractVersion");
        return version instanceof Number number
                && number.intValue() == CapabilityInvocationRequest.CONTRACT_VERSION;
    }

    private Map<String, Object> error(String code, String qualifiedName, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("service", "reachai-capability-service");
        body.put("code", code);
        body.put("qualifiedName", qualifiedName);
        body.put("message", message);
        return body;
    }
}
