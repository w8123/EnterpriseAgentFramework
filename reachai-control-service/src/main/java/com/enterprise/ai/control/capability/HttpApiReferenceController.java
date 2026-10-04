package com.enterprise.ai.control.capability;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformPermissions;
import com.enterprise.ai.control.identity.PlatformRequestAuthorization;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Objects;

/** Project-scoped API usage view; owner identity is resolved before querying Runtime's reverse index. */
@RestController
@RequiredArgsConstructor
public class HttpApiReferenceController {
    private final CapabilityReviewGateway capability;
    private final PlatformRequestAuthorization authorization;
    private final CapabilityChangeImpactService impact;

    @GetMapping("/api/apis/{id}/references")
    public ResponseEntity<Object> references(HttpServletRequest request, @PathVariable long id) {
        PlatformAuthenticatedSession session = authorization.requirePermission(request, PlatformPermissions.PLATFORM_READ);
        if (id <= 0 || session.user() == null || session.user().getId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "API 标识或登录身份无效");
        }
        String actorId = String.valueOf(session.user().getId());
        ResponseEntity<Object> response = capability.getHttpApi(id, actorId);
        if (response == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "API owner 暂不可用");
        if (!response.getStatusCode().is2xxSuccessful()) return response;
        if (!(response.getBody() instanceof Map<?, ?> detail)
                || !(detail.get("summary") instanceof Map<?, ?> summary)
                || !(summary.get("id") instanceof Number ownerId) || ownerId.longValue() != id
                || !(summary.get("projectCode") instanceof String projectCode) || projectCode.isBlank()
                || !(summary.get("qualifiedName") instanceof String qualifiedName)
                || !qualifiedName.startsWith("http-api:" + projectCode + ":")
                || !(summary.get("projectId") instanceof Number projectId) || projectId.longValue() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "API owner 返回无效身份");
        }
        authorization.requireResourcePermission(request, PlatformPermissions.PLATFORM_READ,
                "PROJECT", null, projectCode);
        Map<String, Object> evidence = impact.references(projectCode, qualifiedName, qualifiedName, actorId);
        return ResponseEntity.ok(Objects.requireNonNull(evidence));
    }
}
