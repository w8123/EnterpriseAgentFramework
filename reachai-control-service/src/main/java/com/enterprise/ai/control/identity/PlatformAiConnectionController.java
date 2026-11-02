package com.enterprise.ai.control.identity;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** The owner is always the authenticated platform user, never a request parameter. */
@RestController
@RequestMapping("/api/me/ai-connections")
@RequiredArgsConstructor
public class PlatformAiConnectionController {
    private final PlatformAiConnectionService connections;
    private final PlatformRequestAuthorization authorization;

    @GetMapping
    public ResponseEntity<List<PlatformAiConnectionService.ConnectionView>> list(HttpServletRequest request) {
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(connections.list(authorization.requireAuthenticated(request)));
    }

    @PostMapping
    public ResponseEntity<PlatformAiConnectionService.CreatedConnection> create(
            HttpServletRequest request, @RequestBody PlatformAiConnectionService.CreateConnection command) {
        return ResponseEntity.status(HttpStatus.CREATED).header("Cache-Control", "no-store")
                .body(connections.create(authorization.requireAuthenticated(request), command));
    }

    @PostMapping("/{id}/revoke")
    public ResponseEntity<PlatformAiConnectionService.ConnectionView> revoke(
            HttpServletRequest request, @PathVariable long id) {
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(connections.revoke(authorization.requireAuthenticated(request), id));
    }
}
