package com.enterprise.ai.runtime.compat;

import com.enterprise.ai.runtime.debug.RuntimeExecutableDebugSessionService;
import com.enterprise.ai.runtime.debug.RuntimeDebugSessionOwner;
import com.enterprise.ai.runtime.internalauth.VerifiedInternalServiceAuth;
import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequiredArgsConstructor
public class RuntimeDebugSessionCompatibilityController {

    private final RuntimeExecutableDebugSessionService debugSessionService;

    @PostMapping(path = "/api/runtime/debug-sessions")
    public ResponseEntity<RuntimeExecutableDebugSessionService.SessionView> create(
            @RequestBody RuntimeExecutableDebugSessionService.CreateRequest request, HttpServletRequest httpRequest) {
        return ResponseEntity.ok(debugSessionService.create(owner(httpRequest), request));
    }

    @PostMapping(path = "/api/runtime/debug-sessions/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamCreate(@RequestBody RuntimeExecutableDebugSessionService.CreateRequest request, HttpServletRequest httpRequest) {
        return debugSessionService.streamCreate(owner(httpRequest), request);
    }

    @GetMapping(path = "/api/runtime/debug-sessions/{sessionId}")
    public ResponseEntity<RuntimeExecutableDebugSessionService.SessionView> get(@PathVariable String sessionId, HttpServletRequest httpRequest) {
        return ResponseEntity.ok(debugSessionService.get(owner(httpRequest), sessionId));
    }

    @GetMapping(path = "/api/runtime/debug-sessions/by-creation-key/{key}")
    public ResponseEntity<RuntimeExecutableDebugSessionService.SessionView> getByCreationKey(@PathVariable String key, HttpServletRequest httpRequest) {
        return ResponseEntity.ok(debugSessionService.getByCreationKey(owner(httpRequest),key));
    }

    @PostMapping(path = "/api/runtime/debug-sessions/{sessionId}/submit")
    public ResponseEntity<RuntimeExecutableDebugSessionService.SessionView> submit(
            @PathVariable String sessionId,
            @RequestBody RuntimeExecutableDebugSessionService.SubmitRequest request, HttpServletRequest httpRequest) {
        return ResponseEntity.ok(debugSessionService.submit(owner(httpRequest), sessionId, request));
    }

    @PostMapping(path = "/api/runtime/debug-sessions/{sessionId}/submit/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamSubmit(
            @PathVariable String sessionId,
            @RequestBody RuntimeExecutableDebugSessionService.SubmitRequest request, HttpServletRequest httpRequest) {
        return debugSessionService.streamSubmit(owner(httpRequest), sessionId, request);
    }

    @PostMapping(path = "/api/runtime/debug-sessions/{sessionId}/cancel")
    public ResponseEntity<RuntimeExecutableDebugSessionService.SessionView> cancel(@PathVariable String sessionId, HttpServletRequest httpRequest) {
        return ResponseEntity.ok(debugSessionService.cancel(owner(httpRequest), sessionId));
    }

    private RuntimeDebugSessionOwner owner(HttpServletRequest request) {
        Object attribute = request == null ? null : request.getAttribute(VerifiedInternalServiceAuth.REQUEST_ATTR);
        if (!(attribute instanceof VerifiedInternalServiceAuth verified)
                || !InternalServiceAuthHeaders.CALLER_CONTROL.equals(verified.caller())
                || !InternalServiceAuthHeaders.IDENTITY_SOURCE_PLATFORM_SESSION.equals(verified.identitySource()))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Platform session authentication is required");
        return new RuntimeDebugSessionOwner(verified.identityTenantId(), verified.identityUserId());
    }
}
