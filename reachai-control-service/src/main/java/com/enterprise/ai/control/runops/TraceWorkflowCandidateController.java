package com.enterprise.ai.control.runops;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformConsoleAuthInterceptor;
import com.enterprise.ai.control.identity.PlatformPermissions;
import com.enterprise.ai.control.runops.TraceWorkflowCandidateApplicationService.CandidateTaskView;
import com.enterprise.ai.control.runops.TraceWorkflowCandidateApplicationService.CreateCandidateTaskRequest;
import com.enterprise.ai.control.runops.TraceWorkflowCandidateEligibility.EligibilityView;
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
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * The route rule only proves run-center read somewhere, so each call checks the trace's own project: reading
 * eligibility needs run-center read there, and creating the candidate task needs the AI coding console's write
 * permission there. The task creator is the session account.
 */
@RestController
@RequestMapping("/api/runops/traces/{traceId}/workflow-candidate")
@RequiredArgsConstructor
public class TraceWorkflowCandidateController {

    private final TraceWorkflowCandidateApplicationService service;

    @GetMapping("/eligibility")
    public ResponseEntity<EligibilityView> eligibility(
            HttpServletRequest request,
            @PathVariable String traceId) {
        PlatformAuthenticatedSession session = session(request);
        EligibilityView eligibility = service.eligibility(traceId);
        requireProject(session, eligibility, List.of(PlatformPermissions.RUNOPS_READ));
        return ResponseEntity.ok(eligibility);
    }

    @PostMapping("/tasks")
    public ResponseEntity<CandidateTaskView> createTask(
            HttpServletRequest request,
            @PathVariable String traceId,
            @RequestBody(required = false) CreateCandidateTaskRequest body) {
        PlatformAuthenticatedSession session = session(request);
        EligibilityView eligibility = service.eligibility(traceId);
        requireProject(session, eligibility, List.of(PlatformPermissions.RUNOPS_READ));
        requireProject(session, eligibility, List.of(PlatformPermissions.PLATFORM_WRITE, PlatformPermissions.WORKFLOW_WRITE));
        CreateCandidateTaskRequest attributed = new CreateCandidateTaskRequest(
                body == null ? null : body.executorProvider(), session.user().getUsername());
        return ResponseEntity.ok(service.createOrReuse(traceId, attributed));
    }

    private static PlatformAuthenticatedSession session(HttpServletRequest request) {
        Object candidate = request == null ? null : request.getAttribute(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE);
        if (!(candidate instanceof PlatformAuthenticatedSession session) || session.user() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "live ReachAI platform login is required");
        }
        return session;
    }

    private static void requireProject(PlatformAuthenticatedSession session, EligibilityView eligibility, List<String> anyOf) {
        String projectCode = eligibility == null || eligibility.projectCode() == null || eligibility.projectCode().isBlank()
                ? null : eligibility.projectCode().trim();
        if (anyOf.stream().noneMatch(permission -> session.hasResourcePermission(permission, "PROJECT", null, projectCode))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "permission on the trace's project is required");
        }
    }
}
