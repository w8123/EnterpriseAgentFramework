package com.enterprise.ai.agent.controller;

import com.enterprise.ai.agent.debug.ExecutableDebugSessionService;
import com.enterprise.ai.agent.debug.ExecutableDebugSessionService.CreateRequest;
import com.enterprise.ai.agent.debug.ExecutableDebugSessionService.ExecutableDebugSessionView;
import com.enterprise.ai.agent.debug.ExecutableDebugSessionService.SubmitRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/runtime/debug-sessions")
@RequiredArgsConstructor
public class ExecutableDebugSessionController {

    private final ExecutableDebugSessionService executableDebugSessionService;

    @PostMapping
    public ResponseEntity<ExecutableDebugSessionView> create(@RequestBody CreateRequest request) {
        return ResponseEntity.ok(executableDebugSessionService.create(request));
    }

    @GetMapping("/{sessionId}")
    public ResponseEntity<ExecutableDebugSessionView> get(@PathVariable String sessionId) {
        return ResponseEntity.ok(executableDebugSessionService.get(sessionId));
    }

    @PostMapping("/{sessionId}/submit")
    public ResponseEntity<ExecutableDebugSessionView> submit(@PathVariable String sessionId,
                                                             @RequestBody SubmitRequest request) {
        return ResponseEntity.ok(executableDebugSessionService.submit(sessionId, request));
    }

    @PostMapping("/{sessionId}/cancel")
    public ResponseEntity<ExecutableDebugSessionView> cancel(@PathVariable String sessionId) {
        return ResponseEntity.ok(executableDebugSessionService.cancel(sessionId));
    }
}
