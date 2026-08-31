package com.enterprise.ai.control.a2a.api.management;

import com.enterprise.ai.control.a2a.application.A2aPageView;
import com.enterprise.ai.control.a2a.application.task.A2aTaskManagementService;
import com.enterprise.ai.control.a2a.application.task.A2aTaskManagementService.TaskDetailView;
import com.enterprise.ai.control.a2a.application.task.A2aTaskManagementService.TaskSummaryView;
import com.enterprise.ai.control.a2a.application.task.A2aTaskPayloadService;
import com.enterprise.ai.control.a2a.application.task.A2aTaskPayloadService.PayloadView;
import com.enterprise.ai.control.identity.PlatformAuthAuditService;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

@RestController
@RequestMapping("/api/a2a-hub/tasks")
@RequiredArgsConstructor
public class A2aTaskManagementController {

    private final A2aTaskManagementService service;
    private final A2aTaskPayloadService payloads;
    private final A2aHubManagementAccess access;
    private final PlatformAuthAuditService auditService;

    @GetMapping
    public A2aPageView<TaskSummaryView> list(
            HttpServletRequest request,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String direction,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) Long publicationId,
            @RequestParam(required = false) Long remoteAgentId,
            @RequestParam(required = false) Long principalId,
            @RequestParam(required = false) String tenantScope,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            OffsetDateTime submittedAfter,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            OffsetDateTime submittedBefore,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset) {
        access.require(request, A2aHubManagementAccess.READ);
        return service.list(
                search, direction, state, publicationId, remoteAgentId, principalId, tenantScope,
                utc(submittedAfter), utc(submittedBefore), limit, offset);
    }

    @GetMapping("/{taskId}")
    public TaskDetailView detail(
            HttpServletRequest request,
            @PathVariable String taskId,
            @RequestParam(required = false) String direction) {
        access.require(request, A2aHubManagementAccess.READ);
        return service.detail(taskId, direction);
    }

    @PostMapping("/{taskId}:cancel")
    public TaskSummaryView cancel(
            HttpServletRequest request,
            @PathVariable String taskId,
            @RequestParam(required = false) String direction) {
        PlatformAuthenticatedSession session = access.require(
                request, A2aHubManagementAccess.OPERATE_TASKS);
        TaskSummaryView result = service.cancel(taskId, direction, access.actor(session));
        auditService.record(session, "A2A_TASK_CANCEL_REQUESTED", "A2A_TASK", taskId,
                Map.of("taskId", taskId, "resolvedDirection", result.direction()));
        return result;
    }

    @GetMapping("/{taskId}/messages/{messageId}/payload")
    public ResponseEntity<PayloadView> messagePayload(
            HttpServletRequest request,
            @PathVariable String taskId,
            @PathVariable String messageId,
            @RequestParam(required = false) String direction) {
        PlatformAuthenticatedSession session = access.require(
                request, A2aHubManagementAccess.READ_PAYLOAD);
        PayloadView result = payloads.message(taskId, direction, messageId);
        auditService.record(session, "A2A_MESSAGE_PAYLOAD_READ", "A2A_MESSAGE", messageId,
                Map.of("taskId", taskId, "messageId", messageId));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }

    @GetMapping("/{taskId}/artifacts/{artifactId}/payload")
    public ResponseEntity<PayloadView> artifactPayload(
            HttpServletRequest request,
            @PathVariable String taskId,
            @PathVariable String artifactId,
            @RequestParam(required = false) String direction) {
        PlatformAuthenticatedSession session = access.require(
                request, A2aHubManagementAccess.READ_PAYLOAD);
        PayloadView result = payloads.artifact(taskId, direction, artifactId);
        auditService.record(session, "A2A_ARTIFACT_PAYLOAD_READ", "A2A_ARTIFACT", artifactId,
                Map.of("taskId", taskId, "artifactId", artifactId));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }

    private java.time.LocalDateTime utc(OffsetDateTime value) {
        return value == null ? null : value.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }
}
