package com.enterprise.ai.control.internal;

import com.enterprise.ai.control.internalauth.ControlInternalServiceAuthVerifier;
import com.enterprise.ai.control.platform.PageActionEffectReviewService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Runtime-only, signed read; this endpoint cannot approve or alter a declaration. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/control/page-action-effects")
public class InternalPageActionEffectController {
    private final ControlInternalServiceAuthVerifier auth;
    private final PageActionEffectReviewService reviews;
    private final ObjectMapper mapper;

    @PostMapping("/resolve")
    public ResponseEntity<PageActionEffectReviewService.ActiveApproval> resolve(HttpServletRequest request, @RequestBody byte[] body) {
        auth.requireRuntime(request, body);
        try {
            var query = mapper.readValue(body, ResolveRequest.class);
            return reviews.resolveApproved(query.projectCode(), query.pageKey(), query.actionKey())
                    .map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
        } catch (java.io.IOException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid effect lookup", invalid);
        }
    }
    public record ResolveRequest(String projectCode, String pageKey, String actionKey) { }
}
