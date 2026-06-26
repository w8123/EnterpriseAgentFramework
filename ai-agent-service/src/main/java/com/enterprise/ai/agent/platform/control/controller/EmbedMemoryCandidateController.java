package com.enterprise.ai.agent.platform.control.controller;

import com.enterprise.ai.agent.platform.control.context.memory.*;
import com.enterprise.ai.agent.platform.control.identity.EmbedSessionEntity;
import com.enterprise.ai.agent.platform.control.identity.EmbedSessionService;
import com.enterprise.ai.agent.platform.control.identity.EmbedTokenClaims;
import com.enterprise.ai.agent.platform.control.identity.EmbedTokenException;
import com.enterprise.ai.agent.platform.control.identity.EmbedTokenService;
import com.enterprise.ai.common.dto.ApiResult;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/embed/sessions/{sessionId}/memory/candidates")
@RequiredArgsConstructor
public class EmbedMemoryCandidateController {

    private final EmbedTokenService embedTokenService;
    private final EmbedSessionService embedSessionService;
    private final RuntimeUserIdentityResolver runtimeUserIdentityResolver;
    private final ContextMemoryCandidateService candidateService;

    @GetMapping
    public ApiResult<List<ContextMemoryCandidateResponse>> listCandidates(
            @PathVariable String sessionId,
            @RequestHeader("Authorization") String authorization,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Boolean includeExpired,
            @RequestParam(required = false) Integer limit) {
        try {
            RuntimeUserIdentity identity = resolveIdentity(sessionId, authorization);
            ContextMemoryCandidateQueryRequest query = toQuery(identity, status, includeExpired, limit);
            return ApiResult.ok(candidateService.listCandidates(query));
        } catch (EmbedTokenException ex) {
            return ApiResult.fail(401, ex.getMessage());
        } catch (IllegalArgumentException ex) {
            return ApiResult.fail(accessDenied(ex) ? 403 : 400, ex.getMessage());
        }
    }

    @GetMapping("/{id}")
    public ApiResult<ContextMemoryCandidateResponse> getCandidate(
            @PathVariable String sessionId,
            @PathVariable Long id,
            @RequestHeader("Authorization") String authorization) {
        try {
            RuntimeUserIdentity identity = resolveIdentity(sessionId, authorization);
            return ApiResult.ok(candidateService.getCandidate(id, toQuery(identity, null, null, null)));
        } catch (EmbedTokenException ex) {
            return ApiResult.fail(401, ex.getMessage());
        } catch (IllegalArgumentException ex) {
            return ApiResult.fail(accessDenied(ex) ? 403 : 404, ex.getMessage());
        }
    }

    @PostMapping("/{id}/approve")
    public ApiResult<ContextMemoryCandidateResponse> approveCandidate(
            @PathVariable String sessionId,
            @PathVariable Long id,
            @RequestHeader("Authorization") String authorization,
            @RequestBody(required = false) EmbedMemoryCandidateReviewRequest request) {
        try {
            RuntimeUserIdentity identity = resolveIdentity(sessionId, authorization);
            return ApiResult.ok(candidateService.approveCandidate(id, toReview(identity, request)));
        } catch (EmbedTokenException ex) {
            return ApiResult.fail(401, ex.getMessage());
        } catch (IllegalArgumentException ex) {
            return ApiResult.fail(accessDenied(ex) ? 403 : 400, ex.getMessage());
        }
    }

    @PostMapping("/{id}/reject")
    public ApiResult<ContextMemoryCandidateResponse> rejectCandidate(
            @PathVariable String sessionId,
            @PathVariable Long id,
            @RequestHeader("Authorization") String authorization,
            @RequestBody(required = false) EmbedMemoryCandidateReviewRequest request) {
        try {
            RuntimeUserIdentity identity = resolveIdentity(sessionId, authorization);
            return ApiResult.ok(candidateService.rejectCandidate(id, toReview(identity, request)));
        } catch (EmbedTokenException ex) {
            return ApiResult.fail(401, ex.getMessage());
        } catch (IllegalArgumentException ex) {
            return ApiResult.fail(accessDenied(ex) ? 403 : 400, ex.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public ApiResult<ContextMemoryCandidateResponse> deleteCandidate(
            @PathVariable String sessionId,
            @PathVariable Long id,
            @RequestHeader("Authorization") String authorization) {
        try {
            RuntimeUserIdentity identity = resolveIdentity(sessionId, authorization);
            return ApiResult.ok(candidateService.deleteCandidate(id, toQuery(identity, null, null, null)));
        } catch (EmbedTokenException ex) {
            return ApiResult.fail(401, ex.getMessage());
        } catch (IllegalArgumentException ex) {
            return ApiResult.fail(accessDenied(ex) ? 403 : 404, ex.getMessage());
        }
    }

    private RuntimeUserIdentity resolveIdentity(String sessionId, String authorization) {
        EmbedTokenClaims claims = verifyBearer(authorization);
        EmbedSessionEntity session = embedSessionService.requireActiveSession(sessionId, claims);
        return runtimeUserIdentityResolver.resolve(session, claims);
    }

    private ContextMemoryCandidateQueryRequest toQuery(RuntimeUserIdentity identity,
                                                         String status,
                                                         Boolean includeExpired,
                                                         Integer limit) {
        ContextMemoryCandidateQueryRequest query = new ContextMemoryCandidateQueryRequest();
        query.setTenantId(identity.tenantId());
        query.setUserId(identity.userId());
        if (StringUtils.hasText(identity.projectCode())) {
            query.setProjectCode(identity.projectCode());
        }
        if (StringUtils.hasText(status)) {
            query.setStatus(status.trim());
        }
        query.setIncludeExpired(includeExpired);
        query.setLimit(limit);
        return query;
    }

    private ContextMemoryCandidateReviewRequest toReview(RuntimeUserIdentity identity,
                                                           EmbedMemoryCandidateReviewRequest request) {
        ContextMemoryCandidateReviewRequest review = new ContextMemoryCandidateReviewRequest();
        review.setTenantId(identity.tenantId());
        review.setUserId(identity.userId());
        review.setReviewedBy(identity.userId());
        if (request != null) {
            review.setReviewReason(request.getReviewReason());
            review.setConfidence(request.getConfidence());
            review.setTrustLevel(request.getTrustLevel());
        }
        return review;
    }

    private EmbedTokenClaims verifyBearer(String authorization) {
        if (!StringUtils.hasText(authorization) || !authorization.startsWith("Bearer ")) {
            throw new EmbedTokenException("Authorization Bearer embed token is required");
        }
        return embedTokenService.verify(authorization.substring("Bearer ".length()).trim());
    }

    private boolean accessDenied(IllegalArgumentException ex) {
        String message = ex.getMessage();
        return message != null && message.toLowerCase().contains("access denied");
    }
}
