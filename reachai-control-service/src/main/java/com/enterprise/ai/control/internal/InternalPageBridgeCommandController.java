package com.enterprise.ai.control.internal;

import com.enterprise.ai.control.platform.PlatformPageBridgeCommandService;
import com.enterprise.ai.control.platform.PlatformPageBridgeCommandService.PageBridgeExecutionRequest;
import com.enterprise.ai.control.platform.PlatformPageBridgeCommandService.PageBridgeExecutionResponse;
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

    @PostMapping("/execute")
    public ResponseEntity<PageBridgeExecutionResponse> execute(@RequestBody PageBridgeExecutionRequest request) {
        return ResponseEntity.ok(service.execute(request));
    }
}
