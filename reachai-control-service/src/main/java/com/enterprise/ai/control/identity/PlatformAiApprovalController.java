package com.enterprise.ai.control.identity;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** A human reviews and decides only their own platform AI requests. */
@RestController
@RequestMapping("/api/me/ai-approvals")
@RequiredArgsConstructor
public class PlatformAiApprovalController {
    private final PlatformAiApprovalService approvals;
    private final PlatformRequestAuthorization authorization;

    @GetMapping
    public ResponseEntity<List<PlatformAiApprovalService.ApprovalView>> list(HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(approvals.list(authorization.requireAuthenticated(request)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<PlatformAiApprovalService.ApprovalView> review(HttpServletRequest request, @PathVariable String id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(approvals.review(authorization.requireAuthenticated(request), id));
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<PlatformAiApprovalService.ApprovalView> approve(HttpServletRequest request, @PathVariable String id,
                                                                          @RequestBody Decision body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(approvals.decide(authorization.requireAuthenticated(request), id,
                        body == null ? null : body.expectedDigest(), true));
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<PlatformAiApprovalService.ApprovalView> reject(HttpServletRequest request, @PathVariable String id,
                                                                         @RequestBody Decision body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(approvals.decide(authorization.requireAuthenticated(request), id,
                        body == null ? null : body.expectedDigest(), false));
    }

    public record Decision(String expectedDigest) { }
}
