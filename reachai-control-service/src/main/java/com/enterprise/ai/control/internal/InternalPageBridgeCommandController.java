package com.enterprise.ai.control.internal;

import com.enterprise.ai.control.platform.PlatformPageBridgeCommandService;
import com.enterprise.ai.control.platform.PlatformPageBridgeCommandService.PageBridgeContextResolution;
import com.enterprise.ai.control.platform.PlatformPageBridgeCommandService.PageBridgeContextResolutionRequest;
import com.enterprise.ai.control.platform.PlatformPageBridgeCommandService.PageBridgeExecutionRequest;
import com.enterprise.ai.control.platform.PlatformPageBridgeCommandService.PageBridgeExecutionResponse;
import com.enterprise.ai.control.internalauth.ControlInternalServiceAuthVerifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/control/page-bridge")
@RequiredArgsConstructor
public class InternalPageBridgeCommandController {

    private final PlatformPageBridgeCommandService service;
    private final ControlInternalServiceAuthVerifier authVerifier;
    private final ObjectMapper objectMapper;

    @PostMapping("/execute")
    public ResponseEntity<PageBridgeExecutionResponse> execute(HttpServletRequest request, @RequestBody byte[] body) {
        authVerifier.requireRuntime(request, body);
        return ResponseEntity.ok(service.execute(parse(body, PageBridgeExecutionRequest.class)));
    }

    @PostMapping("/resolve-context")
    public ResponseEntity<PageBridgeContextResolution> resolveContext(
            HttpServletRequest request, @RequestBody byte[] body) {
        authVerifier.requireRuntime(request, body);
        return ResponseEntity.ok(service.resolveContext(parse(body, PageBridgeContextResolutionRequest.class)));
    }

    private <T> T parse(byte[] body, Class<T> type) {
        try { return objectMapper.readValue(body, type); }
        catch (java.io.IOException invalid) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "invalid page bridge request", invalid);
        }
    }
}
