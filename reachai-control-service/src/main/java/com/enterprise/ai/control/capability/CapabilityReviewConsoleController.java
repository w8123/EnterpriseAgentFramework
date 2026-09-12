package com.enterprise.ai.control.capability;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformPermissions;
import com.enterprise.ai.control.identity.PlatformRequestAuthorization;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Platform-console facade for capability snapshot review operations.
 *
 * <p>The Capability service still owns the registry model. Control gives the
 * management console a platform-session boundary and never trusts an operator
 * supplied by the browser.</p>
 */
@RestController
@RequestMapping("/api/capability-review")
@RequiredArgsConstructor
public class CapabilityReviewConsoleController {

    private final CapabilityReviewGateway capabilityReviewGateway;
    private final PlatformRequestAuthorization requestAuthorization;
    private final CapabilityChangeImpactService changeImpactService;

    @GetMapping("/projects/{projectCode}/changes")
    public ResponseEntity<Object> listChanges(HttpServletRequest request, @PathVariable String projectCode,
                                              @RequestParam(defaultValue = "PENDING") String state,
                                              @RequestParam(defaultValue = "") String keyword,
                                              @RequestParam(defaultValue = "1") int current,
                                              @RequestParam(defaultValue = "20") int size) {
        PlatformAuthenticatedSession session = requestAuthorization.requireResourcePermission(
                request, PlatformPermissions.PLATFORM_READ, "PROJECT", null, projectCode);
        return changeImpactService.enrich(capabilityReviewGateway.listChanges(
                projectCode, state, keyword, current, size, actorId(session)), projectCode, actorId(session));
    }

    @PostMapping("/projects/{projectCode}/capabilities/sync")
    public ResponseEntity<Object> syncCapabilities(HttpServletRequest request,
                                                   @PathVariable String projectCode,
                                                   @RequestBody(required = false) Map<String, Object> body) {
        PlatformAuthenticatedSession session = requestAuthorization.requireResourcePermission(
                request, PlatformPermissions.PLATFORM_WRITE, "PROJECT", null, projectCode);
        return capabilityReviewGateway.createPendingSnapshot(
                projectCode, bodyOrEmpty(body), actorId(session));
    }

    @PostMapping("/projects/{projectCode}/capabilities/diff")
    public ResponseEntity<Object> diffCapabilities(HttpServletRequest request,
                                                   @PathVariable String projectCode,
                                                   @RequestBody(required = false) Map<String, Object> body) {
        PlatformAuthenticatedSession session = requestAuthorization.requireResourcePermission(
                request, PlatformPermissions.PLATFORM_WRITE, "PROJECT", null, projectCode);
        return capabilityReviewGateway.createPendingSnapshot(
                projectCode, bodyOrEmpty(body), actorId(session));
    }

    @GetMapping("/projects/{projectCode}/snapshots")
    public ResponseEntity<Object> listCapabilitySnapshots(HttpServletRequest request,
                                                          @PathVariable String projectCode) {
        PlatformAuthenticatedSession session = requestAuthorization.requireResourcePermission(
                request, PlatformPermissions.PLATFORM_READ, "PROJECT", null, projectCode);
        return capabilityReviewGateway.listSnapshots(projectCode, actorId(session));
    }

    @GetMapping("/projects/{projectCode}/snapshots/{snapshotId}/diff-items")
    public ResponseEntity<Object> listCapabilityDiffItems(HttpServletRequest request,
                                                          @PathVariable String projectCode,
                                                          @PathVariable Long snapshotId) {
        PlatformAuthenticatedSession session = requestAuthorization.requireResourcePermission(
                request, PlatformPermissions.PLATFORM_READ, "PROJECT", null, projectCode);
        return capabilityReviewGateway.listDiffItems(projectCode, snapshotId, actorId(session));
    }

    @PostMapping("/projects/{projectCode}/diff-items/{diffItemId}/review")
    public ResponseEntity<Object> reviewCapabilityDiffItem(HttpServletRequest request,
                                                           @PathVariable String projectCode,
                                                           @PathVariable Long diffItemId,
                                                           @RequestBody(required = false) Map<String, Object> body) {
        PlatformAuthenticatedSession session = requestAuthorization.requireResourcePermission(
                request, PlatformPermissions.PLATFORM_WRITE, "PROJECT", null, projectCode);
        return capabilityReviewGateway.reviewDiffItem(
                projectCode, diffItemId, withAuthenticatedOperator(body, session), actorId(session));
    }

    @PostMapping("/projects/{projectCode}/diff-items/{diffItemId}/rollback")
    public ResponseEntity<Object> rollbackCapabilityDiffItem(HttpServletRequest request,
                                                             @PathVariable String projectCode,
                                                             @PathVariable Long diffItemId,
                                                             @RequestBody(required = false) Map<String, Object> body) {
        PlatformAuthenticatedSession session = requestAuthorization.requireResourcePermission(
                request, PlatformPermissions.PLATFORM_WRITE, "PROJECT", null, projectCode);
        return capabilityReviewGateway.rollbackDiffItem(
                projectCode, diffItemId, withAuthenticatedOperator(body, session), actorId(session));
    }

    private Map<String, Object> bodyOrEmpty(Map<String, Object> body) {
        return body == null ? Map.of() : body;
    }

    private String actorId(PlatformAuthenticatedSession session) {
        if (session.user() == null || session.user().getId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "authenticated platform userId is required");
        }
        return String.valueOf(session.user().getId());
    }

    private Map<String, Object> withAuthenticatedOperator(Map<String, Object> body,
                                                          PlatformAuthenticatedSession session) {
        if (session.user() == null || !StringUtils.hasText(session.user().getUsername())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "authenticated platform username is required");
        }
        Map<String, Object> forwarded = new LinkedHashMap<>();
        if (body != null) {
            forwarded.putAll(body);
        }
        forwarded.put("operator", session.user().getUsername());
        return forwarded;
    }
}
