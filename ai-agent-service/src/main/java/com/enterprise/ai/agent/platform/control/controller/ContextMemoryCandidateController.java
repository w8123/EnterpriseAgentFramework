package com.enterprise.ai.agent.platform.control.controller;

import com.enterprise.ai.agent.platform.control.context.memory.*;
import com.enterprise.ai.agent.platform.control.context.ContextRuntimeUserAccessService;
import com.enterprise.ai.agent.platform.control.auth.PlatformAuthContext;
import com.enterprise.ai.agent.platform.control.auth.PlatformPrincipal;
import com.enterprise.ai.common.dto.ApiResult;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/context/memory/candidates")
@RequiredArgsConstructor
public class ContextMemoryCandidateController {

    private final ContextMemoryCandidateService candidateService;
    private final ContextRuntimeUserAccessService runtimeUserAccessService;

    @PostMapping
    public ApiResult<ContextMemoryCandidateResponse> createCandidate(
            @RequestBody ContextMemoryCandidateCreateRequest request) {
        try {
            if (isProjectDev(request.getMemoryLane())) {
                bindProjectDevScope(request.getTenantId(), request.getProjectCode(), request.getProjectId());
                if (!StringUtils.hasText(request.getProposedBy())) {
                    request.setProposedBy(currentActorUserId());
                }
                return ApiResult.ok(candidateService.createCandidate(request));
            }
            RuntimeUserScope runtimeScope = bindRuntimeUserScope(request.getTenantId(), request.getUserId(),
                    request.getGlobalUserId(), request.getExternalUserId(), request.getProjectCode(),
                    request.getProjectId(), false);
            request.setUserId(runtimeScope.userId());
            if (!StringUtils.hasText(request.getProposedBy())) {
                request.setProposedBy(currentActorUserId());
            }
            return ApiResult.ok(candidateService.createCandidate(request));
        } catch (IllegalArgumentException ex) {
            return ApiResult.fail(accessDenied(ex) ? 403 : 400, ex.getMessage());
        }
    }

    @GetMapping
    public ApiResult<List<ContextMemoryCandidateResponse>> listCandidates(ContextMemoryCandidateQueryRequest query) {
        try {
            if (isProjectDev(query.getMemoryLane())) {
                bindProjectDevScope(query.getTenantId(), query.getProjectCode(), query.getProjectId());
                return ApiResult.ok(candidateService.listCandidates(query));
            }
            RuntimeUserScope runtimeScope = bindRuntimeUserScope(query.getTenantId(), query.getUserId(), null, null,
                    query.getProjectCode(), query.getProjectId(), true);
            query.setUserId(runtimeScope.userId());
            return ApiResult.ok(candidateService.listCandidates(query));
        } catch (IllegalArgumentException ex) {
            return ApiResult.fail(accessDenied(ex) ? 403 : 400, ex.getMessage());
        }
    }

    @GetMapping("/{id}")
    public ApiResult<ContextMemoryCandidateResponse> getCandidate(@PathVariable Long id,
                                                                    ContextMemoryCandidateQueryRequest query) {
        try {
            if (isProjectDev(query.getMemoryLane())) {
                bindProjectDevScope(query.getTenantId(), query.getProjectCode(), query.getProjectId());
                return ApiResult.ok(candidateService.getCandidate(id, query));
            }
            RuntimeUserScope runtimeScope = bindRuntimeUserScope(query.getTenantId(), query.getUserId(), null, null,
                    query.getProjectCode(), query.getProjectId(), true);
            query.setUserId(runtimeScope.userId());
            return ApiResult.ok(candidateService.getCandidate(id, query));
        } catch (IllegalArgumentException ex) {
            return ApiResult.fail(accessDenied(ex) ? 403 : 404, ex.getMessage());
        }
    }

    @PutMapping("/{id}")
    public ApiResult<ContextMemoryCandidateResponse> updateCandidate(@PathVariable Long id,
                                                                      @RequestBody ContextMemoryCandidateUpdateRequest request) {
        try {
            if (isProjectDev(request.getMemoryLane())) {
                bindProjectDevScope(request.getTenantId(), request.getProjectCode(), request.getProjectId());
                request.setUpdatedBy(requireProjectDevActor(request.getUpdatedBy()));
                return ApiResult.ok(candidateService.updateCandidate(id, request));
            }
            RuntimeUserScope runtimeScope = bindRuntimeUserScope(request.getTenantId(), request.getUserId(), null, null,
                    request.getProjectCode(), request.getProjectId(), true);
            request.setUserId(runtimeScope.userId());
            request.setUpdatedBy(requireSelfServiceActor(request.getUpdatedBy()));
            return ApiResult.ok(candidateService.updateCandidate(id, request));
        } catch (IllegalArgumentException ex) {
            return ApiResult.fail(accessDenied(ex) ? 403 : 400, ex.getMessage());
        }
    }

    @PostMapping("/{id}/approve")
    public ApiResult<ContextMemoryCandidateResponse> approveCandidate(@PathVariable Long id,
                                                                        @RequestBody ContextMemoryCandidateReviewRequest request) {
        try {
            if (isProjectDev(request.getMemoryLane())) {
                bindProjectDevScope(request.getTenantId(), request.getProjectCode(), request.getProjectId());
                request.setReviewedBy(requireProjectDevReviewer(request.getReviewedBy()));
                return ApiResult.ok(candidateService.approveCandidate(id, request));
            }
            RuntimeUserScope runtimeScope = bindRuntimeUserScope(request.getTenantId(), request.getUserId(), null, null,
                    request.getProjectCode(), request.getProjectId(), true);
            request.setUserId(runtimeScope.userId());
            request.setReviewedBy(requireSelfServiceReviewer(request.getReviewedBy()));
            return ApiResult.ok(candidateService.approveCandidate(id, request));
        } catch (IllegalArgumentException ex) {
            return ApiResult.fail(accessDenied(ex) ? 403 : 400, ex.getMessage());
        }
    }

    @PostMapping("/batch/approve")
    public ApiResult<List<ContextMemoryCandidateResponse>> approveCandidateBatch(
            @RequestBody ContextMemoryCandidateBatchReviewRequest request) {
        try {
            if (isProjectDev(request.getMemoryLane())) {
                bindProjectDevScope(request.getTenantId(), request.getProjectCode(), request.getProjectId());
                request.setReviewedBy(requireProjectDevReviewer(request.getReviewedBy()));
                return ApiResult.ok(candidateService.approveCandidates(request));
            }
            RuntimeUserScope runtimeScope = bindRuntimeUserScope(request.getTenantId(), request.getUserId(), null, null,
                    request.getProjectCode(), request.getProjectId(), true);
            request.setUserId(runtimeScope.userId());
            request.setReviewedBy(requireSelfServiceReviewer(request.getReviewedBy()));
            return ApiResult.ok(candidateService.approveCandidates(request));
        } catch (IllegalArgumentException ex) {
            return ApiResult.fail(accessDenied(ex) ? 403 : 400, ex.getMessage());
        }
    }

    @PostMapping("/{id}/reject")
    public ApiResult<ContextMemoryCandidateResponse> rejectCandidate(@PathVariable Long id,
                                                                       @RequestBody ContextMemoryCandidateReviewRequest request) {
        try {
            if (isProjectDev(request.getMemoryLane())) {
                bindProjectDevScope(request.getTenantId(), request.getProjectCode(), request.getProjectId());
                request.setReviewedBy(requireProjectDevReviewer(request.getReviewedBy()));
                return ApiResult.ok(candidateService.rejectCandidate(id, request));
            }
            RuntimeUserScope runtimeScope = bindRuntimeUserScope(request.getTenantId(), request.getUserId(), null, null,
                    request.getProjectCode(), request.getProjectId(), true);
            request.setUserId(runtimeScope.userId());
            request.setReviewedBy(requireSelfServiceReviewer(request.getReviewedBy()));
            return ApiResult.ok(candidateService.rejectCandidate(id, request));
        } catch (IllegalArgumentException ex) {
            return ApiResult.fail(accessDenied(ex) ? 403 : 400, ex.getMessage());
        }
    }

    @PostMapping("/batch/reject")
    public ApiResult<List<ContextMemoryCandidateResponse>> rejectCandidateBatch(
            @RequestBody ContextMemoryCandidateBatchReviewRequest request) {
        try {
            if (isProjectDev(request.getMemoryLane())) {
                bindProjectDevScope(request.getTenantId(), request.getProjectCode(), request.getProjectId());
                request.setReviewedBy(requireProjectDevReviewer(request.getReviewedBy()));
                return ApiResult.ok(candidateService.rejectCandidates(request));
            }
            RuntimeUserScope runtimeScope = bindRuntimeUserScope(request.getTenantId(), request.getUserId(), null, null,
                    request.getProjectCode(), request.getProjectId(), true);
            request.setUserId(runtimeScope.userId());
            request.setReviewedBy(requireSelfServiceReviewer(request.getReviewedBy()));
            return ApiResult.ok(candidateService.rejectCandidates(request));
        } catch (IllegalArgumentException ex) {
            return ApiResult.fail(accessDenied(ex) ? 403 : 400, ex.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public ApiResult<ContextMemoryCandidateResponse> deleteCandidate(@PathVariable Long id,
                                                                       ContextMemoryCandidateQueryRequest query) {
        try {
            if (isProjectDev(query.getMemoryLane())) {
                bindProjectDevScope(query.getTenantId(), query.getProjectCode(), query.getProjectId());
                query.setUserId(currentActorUserId());
                return ApiResult.ok(candidateService.deleteCandidate(id, query));
            }
            RuntimeUserScope runtimeScope = bindRuntimeUserScope(query.getTenantId(), query.getUserId(), null, null,
                    query.getProjectCode(), query.getProjectId(), true);
            query.setUserId(runtimeScope.userId());
            return ApiResult.ok(candidateService.deleteCandidate(id, query));
        } catch (IllegalArgumentException ex) {
            return ApiResult.fail(accessDenied(ex) ? 403 : 404, ex.getMessage());
        }
    }

    private RuntimeUserScope bindRuntimeUserScope(String tenantId,
                                                  String userId,
                                                  String globalUserId,
                                                  String externalUserId,
                                                  String projectCode,
                                                  Long projectId,
                                                  boolean allowMappedReviewer) {
        PlatformPrincipal principal = requirePlatformPrincipal();
        if (!StringUtils.hasText(tenantId)) {
            throw new IllegalArgumentException("tenantId is required");
        }
        String actorUserId = currentActorUserId();
        String requested = candidateService.resolveUserId(globalUserId, externalUserId, userId);
        if (!StringUtils.hasText(requested)) {
            return new RuntimeUserScope(actorUserId);
        }
        String requestedUserId = requested.trim();
        if (!requestedUserId.equals(actorUserId)
                && (!allowMappedReviewer
                || !runtimeUserAccessService.canReviewRuntimeUser(
                principal, tenantId.trim(), requestedUserId, projectCode, projectId))) {
            throw new IllegalArgumentException("Memory candidate access denied");
        }
        return new RuntimeUserScope(requestedUserId);
    }

    private void bindProjectDevScope(String tenantId, String projectCode, Long projectId) {
        requirePlatformPrincipal();
        if (!StringUtils.hasText(tenantId)) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (!StringUtils.hasText(projectCode) && projectId == null) {
            throw new IllegalArgumentException("projectCode or projectId is required");
        }
    }

    private PlatformPrincipal requirePlatformPrincipal() {
        PlatformPrincipal principal = PlatformAuthContext.get();
        if (principal == null || principal.userId() == null) {
            throw new IllegalArgumentException("platform login required");
        }
        return principal;
    }

    private String currentActorUserId() {
        return String.valueOf(requirePlatformPrincipal().userId());
    }

    private String requireSelfServiceReviewer(String requestedReviewer) {
        return requireSelfServiceActor(requestedReviewer);
    }

    private String requireSelfServiceActor(String requestedActor) {
        String actorUserId = currentActorUserId();
        if (StringUtils.hasText(requestedActor) && !requestedActor.trim().equals(actorUserId)) {
            throw new IllegalArgumentException("Memory candidate access denied");
        }
        return actorUserId;
    }

    private String requireProjectDevReviewer(String requestedReviewer) {
        return requireProjectDevActor(requestedReviewer);
    }

    private String requireProjectDevActor(String requestedActor) {
        String actorUserId = currentActorUserId();
        if (StringUtils.hasText(requestedActor) && !requestedActor.trim().equals(actorUserId)) {
            throw new IllegalArgumentException("Memory candidate access denied");
        }
        return actorUserId;
    }

    private boolean isProjectDev(String memoryLane) {
        return "PROJECT_DEV".equalsIgnoreCase(StringUtils.hasText(memoryLane) ? memoryLane.trim() : null);
    }

    private boolean accessDenied(IllegalArgumentException ex) {
        String message = ex.getMessage();
        return message != null && (message.contains("access denied") || message.contains("login required"));
    }

    private record RuntimeUserScope(String userId) {
    }
}
