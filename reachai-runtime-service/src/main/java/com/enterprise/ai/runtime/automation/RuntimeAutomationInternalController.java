package com.enterprise.ai.runtime.automation;

import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.runtime.internalauth.VerifiedInternalServiceAuth;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

import static com.enterprise.ai.runtime.automation.RuntimeAutomationViews.AutomationDetailView;
import static com.enterprise.ai.runtime.automation.RuntimeAutomationViews.AutomationSummaryView;
import static com.enterprise.ai.runtime.automation.RuntimeAutomationViews.OccurrenceView;
import static com.enterprise.ai.runtime.automation.RuntimeAutomationViews.ReadinessView;
import static com.enterprise.ai.runtime.automation.RuntimeAutomationViews.UpsertCommand;

@RestController
@RequiredArgsConstructor
public class RuntimeAutomationInternalController {

    private final RuntimeAutomationService service;

    @GetMapping("/internal/runtime/automations")
    public List<AutomationSummaryView> list(
            HttpServletRequest request,
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false) String projectCode,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "200") int limit) {
        return service.list(tenantId, projectCode, status, keyword, limit, requireActor(request));
    }

    @GetMapping("/internal/runtime/automations/readiness")
    public ReadinessView readiness(HttpServletRequest request) {
        requireActor(request);
        return service.readiness();
    }

    @PostMapping("/internal/runtime/automations")
    public ResponseEntity<AutomationDetailView> create(
            HttpServletRequest request,
            @RequestBody UpsertCommand command) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(command, requireActor(request)));
    }

    @GetMapping("/internal/runtime/automations/{automationKey}")
    public AutomationDetailView get(HttpServletRequest request, @PathVariable String automationKey) {
        return service.get(automationKey, requireActor(request));
    }

    @PutMapping("/internal/runtime/automations/{automationKey}")
    public AutomationDetailView update(
            HttpServletRequest request,
            @PathVariable String automationKey,
            @RequestBody UpsertCommand command) {
        return service.update(automationKey, command, requireActor(request));
    }

    @PostMapping("/internal/runtime/automations/{automationKey}:pause")
    public AutomationDetailView pause(
            HttpServletRequest request,
            @PathVariable String automationKey,
            @RequestBody RevisionCommand command) {
        return service.pause(automationKey, revision(command), requireActor(request));
    }

    @PostMapping("/internal/runtime/automations/{automationKey}:resume")
    public AutomationDetailView resume(
            HttpServletRequest request,
            @PathVariable String automationKey,
            @RequestBody RevisionCommand command) {
        return service.resume(automationKey, revision(command), requireActor(request));
    }

    @DeleteMapping("/internal/runtime/automations/{automationKey}")
    public AutomationDetailView archive(
            HttpServletRequest request,
            @PathVariable String automationKey,
            @RequestParam Long expectedRevision) {
        return service.archive(automationKey, expectedRevision, requireActor(request));
    }

    @PostMapping("/internal/runtime/automations/{automationKey}:run")
    public ResponseEntity<OccurrenceView> runNow(
            HttpServletRequest request,
            @PathVariable String automationKey) {
        return ResponseEntity.accepted().body(service.runNow(automationKey, requireActor(request)));
    }

    @GetMapping("/internal/runtime/automations/{automationKey}/occurrences")
    public List<OccurrenceView> occurrences(
            HttpServletRequest request,
            @PathVariable String automationKey,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "200") int limit) {
        return service.occurrences(automationKey, status, limit, requireActor(request));
    }

    @PostMapping("/internal/runtime/automations/{automationKey}/occurrences/{occurrenceId}:retry")
    public ResponseEntity<OccurrenceView> retry(
            HttpServletRequest request,
            @PathVariable String automationKey,
            @PathVariable Long occurrenceId) {
        return ResponseEntity.accepted().body(
                service.retry(automationKey, occurrenceId, requireActor(request)));
    }

    @PostMapping("/internal/runtime/automations/{automationKey}/occurrences/{occurrenceId}:cancel")
    public OccurrenceView cancelOccurrence(
            HttpServletRequest request,
            @PathVariable String automationKey,
            @PathVariable Long occurrenceId,
            @RequestBody(required = false) CancelCommand command) {
        return service.cancelOccurrence(automationKey, occurrenceId,
                command == null ? null : command.reason(), requireActor(request));
    }

    private RuntimeAutomationService.Actor requireActor(HttpServletRequest request) {
        Object value = request == null ? null
                : request.getAttribute(VerifiedInternalServiceAuth.REQUEST_ATTR);
        if (!(value instanceof VerifiedInternalServiceAuth verified)
                || !InternalServiceAuthHeaders.IDENTITY_SOURCE_PLATFORM_SESSION.equals(verified.identitySource())
                || verified.identityUserId() == null || verified.identityUserId().isBlank()) {
            throw new RuntimeAutomationException(
                    HttpStatus.UNAUTHORIZED,
                    "AUTOMATION_PLATFORM_IDENTITY_REQUIRED",
                    "Control-attested platform identity is required");
        }
        return new RuntimeAutomationService.Actor(verified.identityTenantId(), verified.identityUserId());
    }

    private Long revision(RevisionCommand command) {
        return command == null ? null : command.expectedRevision();
    }

    public record RevisionCommand(Long expectedRevision) {
    }

    public record CancelCommand(String reason) {
    }
}
