package com.enterprise.ai.runtime.compat;

import com.enterprise.ai.runtime.debug.RuntimeExecutableDebugSessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class RuntimeDebugSessionCompatibilityController {

    private final RuntimeExecutableDebugSessionService debugSessionService;

    @PostMapping(path = "/api/runtime/debug-sessions")
    public ResponseEntity<RuntimeExecutableDebugSessionService.SessionView> create(
            @RequestBody RuntimeExecutableDebugSessionService.CreateRequest request) {
        return ResponseEntity.ok(debugSessionService.create(request));
    }

    @GetMapping(path = "/api/runtime/debug-sessions/{sessionId}")
    public ResponseEntity<RuntimeExecutableDebugSessionService.SessionView> get(@PathVariable String sessionId) {
        return ResponseEntity.ok(debugSessionService.get(sessionId));
    }

    @PostMapping(path = "/api/runtime/debug-sessions/{sessionId}/submit")
    public ResponseEntity<RuntimeExecutableDebugSessionService.SessionView> submit(
            @PathVariable String sessionId,
            @RequestBody RuntimeExecutableDebugSessionService.SubmitRequest request) {
        return ResponseEntity.ok(debugSessionService.submit(sessionId, request));
    }

    @PostMapping(path = "/api/runtime/debug-sessions/{sessionId}/cancel")
    public ResponseEntity<RuntimeExecutableDebugSessionService.SessionView> cancel(@PathVariable String sessionId) {
        return ResponseEntity.ok(debugSessionService.cancel(sessionId));
    }
}
