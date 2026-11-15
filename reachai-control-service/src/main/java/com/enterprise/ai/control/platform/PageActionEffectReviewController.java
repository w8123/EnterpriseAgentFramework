package com.enterprise.ai.control.platform;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformConsoleAuthInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Management only; no Embed-token or SDK write path is exposed. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/registry/projects/{projectCode}/page-workbench/pages/{pageKey}/actions/{actionKey}/effect-review")
public class PageActionEffectReviewController {
    private final PageActionEffectReviewService service;

    @GetMapping
    public PageActionEffectReviewService.ContractPreview preview(HttpServletRequest request,
            @PathVariable String projectCode, @PathVariable String pageKey, @PathVariable String actionKey) {
        return service.preview(session(request), projectCode, pageKey, actionKey);
    }
    @PostMapping
    public PageActionEffectReviewService.ActiveApproval approve(HttpServletRequest request,
            @PathVariable String projectCode, @PathVariable String pageKey, @PathVariable String actionKey,
            @RequestBody PageActionEffectReviewService.ReviewCommand command) {
        return service.approve(session(request), projectCode, pageKey, actionKey, command);
    }
    @PostMapping("/revoke")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(HttpServletRequest request, @PathVariable String projectCode,
                       @PathVariable String pageKey, @PathVariable String actionKey,
                       @RequestBody PageActionEffectReviewService.RevokeCommand command) {
        service.revoke(session(request), projectCode, pageKey, actionKey, command);
    }
    private PlatformAuthenticatedSession session(HttpServletRequest request) {
        Object value = request.getAttribute(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE);
        if (!(value instanceof PlatformAuthenticatedSession actor))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "live platform session is required");
        return actor;
    }
}
