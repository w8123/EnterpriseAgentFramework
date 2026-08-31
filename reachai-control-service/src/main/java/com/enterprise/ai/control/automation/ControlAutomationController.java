package com.enterprise.ai.control.automation;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class ControlAutomationController {

    private static final String INTERNAL_BASE = "/internal/runtime/automations";

    private final RuntimeAutomationGateway gateway;
    private final AutomationManagementAccess access;

    @GetMapping("/api/automations")
    public ResponseEntity<Object> list(
            HttpServletRequest request,
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false) String projectCode,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "200") int limit) {
        PlatformAuthenticatedSession session = access.require(request, AutomationManagementAccess.READ);
        access.requireProject(session, AutomationManagementAccess.READ, projectCode);
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("tenantId", tenantId);
        query.put("projectCode", projectCode);
        query.put("status", status);
        query.put("keyword", keyword);
        query.put("limit", limit);
        return gateway.get(INTERNAL_BASE, query, access.actorId(session));
    }

    @GetMapping("/api/automations/readiness")
    public ResponseEntity<Object> readiness(HttpServletRequest request) {
        PlatformAuthenticatedSession session = access.require(request, AutomationManagementAccess.READ);
        return gateway.get(INTERNAL_BASE + "/readiness", Map.of(), access.actorId(session));
    }

    @PostMapping("/api/automations")
    public ResponseEntity<Object> create(
            HttpServletRequest request,
            @RequestBody Map<String, Object> body) {
        PlatformAuthenticatedSession session = access.require(request, AutomationManagementAccess.WRITE);
        access.requireProject(session, AutomationManagementAccess.WRITE, text(body.get("projectCode")));
        return gateway.post(INTERNAL_BASE, body, access.actorId(session));
    }

    @GetMapping("/api/automations/{automationKey}")
    public ResponseEntity<Object> get(HttpServletRequest request, @PathVariable String automationKey) {
        PlatformAuthenticatedSession session = access.require(request, AutomationManagementAccess.READ);
        return scopedDetail(session, automationKey, AutomationManagementAccess.READ);
    }

    @PutMapping("/api/automations/{automationKey}")
    public ResponseEntity<Object> update(
            HttpServletRequest request,
            @PathVariable String automationKey,
            @RequestBody Map<String, Object> body) {
        PlatformAuthenticatedSession session = access.require(request, AutomationManagementAccess.WRITE);
        ResponseEntity<Object> current = scopedDetail(session, automationKey, AutomationManagementAccess.WRITE);
        if (!current.getStatusCode().is2xxSuccessful()) return current;
        String requestedProject = text(body.get("projectCode"));
        if (requestedProject != null) {
            access.requireProject(session, AutomationManagementAccess.WRITE, requestedProject);
        }
        return gateway.put(INTERNAL_BASE + "/" + segment(automationKey), body, access.actorId(session));
    }

    @PostMapping("/api/automations/{automationKey}:pause")
    public ResponseEntity<Object> pause(
            HttpServletRequest request,
            @PathVariable String automationKey,
            @RequestBody Map<String, Object> body) {
        return writeAction(request, automationKey, ":pause", body);
    }

    @PostMapping("/api/automations/{automationKey}:resume")
    public ResponseEntity<Object> resume(
            HttpServletRequest request,
            @PathVariable String automationKey,
            @RequestBody Map<String, Object> body) {
        return writeAction(request, automationKey, ":resume", body);
    }

    @DeleteMapping("/api/automations/{automationKey}")
    public ResponseEntity<Object> archive(
            HttpServletRequest request,
            @PathVariable String automationKey,
            @RequestParam Long expectedRevision) {
        PlatformAuthenticatedSession session = access.require(request, AutomationManagementAccess.WRITE);
        ResponseEntity<Object> current = scopedDetail(session, automationKey, AutomationManagementAccess.WRITE);
        if (!current.getStatusCode().is2xxSuccessful()) return current;
        return gateway.delete(INTERNAL_BASE + "/" + segment(automationKey),
                Map.of("expectedRevision", expectedRevision), access.actorId(session));
    }

    @PostMapping("/api/automations/{automationKey}:run")
    public ResponseEntity<Object> runNow(HttpServletRequest request, @PathVariable String automationKey) {
        PlatformAuthenticatedSession session = access.require(request, AutomationManagementAccess.OPERATE);
        ResponseEntity<Object> current = scopedDetail(session, automationKey, AutomationManagementAccess.OPERATE);
        if (!current.getStatusCode().is2xxSuccessful()) return current;
        return gateway.post(INTERNAL_BASE + "/" + segment(automationKey) + ":run",
                null, access.actorId(session));
    }

    @GetMapping("/api/automations/{automationKey}/occurrences")
    public ResponseEntity<Object> occurrences(
            HttpServletRequest request,
            @PathVariable String automationKey,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "200") int limit) {
        PlatformAuthenticatedSession session = access.require(request, AutomationManagementAccess.READ);
        ResponseEntity<Object> current = scopedDetail(session, automationKey, AutomationManagementAccess.READ);
        if (!current.getStatusCode().is2xxSuccessful()) return current;
        return gateway.get(INTERNAL_BASE + "/" + segment(automationKey) + "/occurrences",
                Map.of("status", status == null ? "" : status, "limit", limit), access.actorId(session));
    }

    @PostMapping("/api/automations/{automationKey}/occurrences/{occurrenceId}:retry")
    public ResponseEntity<Object> retry(
            HttpServletRequest request,
            @PathVariable String automationKey,
            @PathVariable Long occurrenceId) {
        PlatformAuthenticatedSession session = access.require(request, AutomationManagementAccess.OPERATE);
        ResponseEntity<Object> current = scopedDetail(session, automationKey, AutomationManagementAccess.OPERATE);
        if (!current.getStatusCode().is2xxSuccessful()) return current;
        return gateway.post(INTERNAL_BASE + "/" + segment(automationKey) + "/occurrences/"
                + occurrenceId + ":retry", null, access.actorId(session));
    }

    @PostMapping("/api/automations/{automationKey}/occurrences/{occurrenceId}:cancel")
    public ResponseEntity<Object> cancelOccurrence(
            HttpServletRequest request,
            @PathVariable String automationKey,
            @PathVariable Long occurrenceId,
            @RequestBody(required = false) Map<String, Object> body) {
        PlatformAuthenticatedSession session = access.require(request, AutomationManagementAccess.OPERATE);
        ResponseEntity<Object> current = scopedDetail(session, automationKey, AutomationManagementAccess.OPERATE);
        if (!current.getStatusCode().is2xxSuccessful()) return current;
        return gateway.post(INTERNAL_BASE + "/" + segment(automationKey) + "/occurrences/"
                + occurrenceId + ":cancel", body == null ? Map.of() : body, access.actorId(session));
    }

    private ResponseEntity<Object> writeAction(HttpServletRequest request,
                                               String automationKey,
                                               String suffix,
                                               Map<String, Object> body) {
        PlatformAuthenticatedSession session = access.require(request, AutomationManagementAccess.WRITE);
        ResponseEntity<Object> current = scopedDetail(session, automationKey, AutomationManagementAccess.WRITE);
        if (!current.getStatusCode().is2xxSuccessful()) return current;
        return gateway.post(INTERNAL_BASE + "/" + segment(automationKey) + suffix,
                body, access.actorId(session));
    }

    private ResponseEntity<Object> scopedDetail(PlatformAuthenticatedSession session,
                                                String automationKey,
                                                String permission) {
        ResponseEntity<Object> detail = gateway.get(
                INTERNAL_BASE + "/" + segment(automationKey), Map.of(), access.actorId(session));
        if (detail.getStatusCode().is2xxSuccessful()) {
            access.requireProject(session, permission, access.projectCode(detail.getBody()));
        }
        return detail;
    }

    private String text(Object value) {
        if (value == null) return null;
        String normalized = String.valueOf(value).trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private String segment(String value) {
        if (value == null || !value.matches("aut_[a-f0-9]{32}")) {
            throw new IllegalArgumentException("invalid Automation key");
        }
        return value;
    }
}
