package com.enterprise.ai.control.aicoding.api;

import com.enterprise.ai.control.aicoding.application.AiCodingHandoffApplicationService;
import com.enterprise.ai.control.aicoding.application.AiCodingTaskApplicationService;
import com.enterprise.ai.control.aicoding.application.AiCodingManagedExecutionService;
import com.enterprise.ai.control.aicoding.application.AiCodingManagedExecutionStreamService;
import com.enterprise.ai.control.aicoding.application.AiCodingManagedExecutionService.ManagedApprovalDecisionCommand;
import com.enterprise.ai.control.aicoding.application.AiCodingManagedExecutionService.ManagedExecutionDetailView;
import com.enterprise.ai.control.aicoding.application.AiCodingManagedExecutionService.ManagedExecutionStartCommand;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.AnswerQuestionCommand;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.AcceptanceVerificationView;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.CreateTaskCommand;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.HandoffIssueCommand;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.HandoffPackageView;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskDetailView;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskView;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformPermissions;
import com.enterprise.ai.control.identity.PlatformConsoleAuthInterceptor;
import com.enterprise.ai.control.managed.ControlManagedExecutionRuntimeClient.ArtifactContent;
import com.enterprise.ai.control.managed.ControlManagedExecutionRuntimeClient.ArtifactView;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/ai-coding-console/tasks")
@RequiredArgsConstructor
public class AiCodingTaskConsoleController {

    private final AiCodingTaskApplicationService taskService;
    private final AiCodingHandoffApplicationService handoffService;
    private final AiCodingManagedExecutionService managedExecutionService;
    private final AiCodingManagedExecutionStreamService managedExecutionStreamService;

    @PostMapping
    public ResponseEntity<TaskView> create(@RequestBody CreateTaskCommand command, HttpServletRequest request) {
        PlatformAuthenticatedSession session = session(request);
        if (command == null) throw new IllegalArgumentException("task command is required");
        requireProject(session, command.projectCode(), true);
        return ResponseEntity.ok(taskService.create(new CreateTaskCommand(command.projectId(), command.projectCode(), command.taskKind(),
                command.executorProvider(), command.title(), command.objective(), session.user().getUsername(), command.targets(),
                command.executionMode(), command.sandboxProfile())));
    }

    @GetMapping
    public ResponseEntity<List<TaskView>> list(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String projectCode,
            @RequestParam(required = false) String taskKind,
            @RequestParam(required = false) String executionStatus,
            @RequestParam(defaultValue = "100") int limit,
            HttpServletRequest request) {
        PlatformAuthenticatedSession session = session(request);
        return ResponseEntity.ok(taskService.list(
                projectId,
                projectCode,
                taskKind,
                executionStatus,
                limit).stream().filter(task -> allows(session, task.projectCode(), false)).toList());
    }

    @GetMapping("/{taskId}")
    public ResponseEntity<TaskDetailView> detail(@PathVariable String taskId, HttpServletRequest request) {
        return ResponseEntity.ok(requireTask(request, taskId, false));
    }

    @PostMapping("/{taskId}/managed-execution")
    public ResponseEntity<ManagedExecutionDetailView> startManagedExecution(
            @PathVariable String taskId,
            @RequestBody(required = false) ManagedExecutionStartCommand command,
            HttpServletRequest request) {
        requireTask(request, taskId, true);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(managedExecutionService.start(
                        taskId, command, actorUserId(request)));
    }

    @GetMapping("/{taskId}/managed-execution")
    public ResponseEntity<ManagedExecutionDetailView> managedExecution(
            @PathVariable String taskId,
            HttpServletRequest request) {
        requireTask(request, taskId, false);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(managedExecutionService.detail(taskId, actorUserId(request)));
    }

    @GetMapping(
            path = "/{taskId}/managed-execution/events",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> managedExecutionEvents(
            @PathVariable String taskId,
            HttpServletRequest request) {
        requireTask(request, taskId, false);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .header("X-Accel-Buffering", "no")
                .body(managedExecutionStreamService.stream(
                        taskId, actorUserId(request)));
    }

    @GetMapping("/{taskId}/managed-execution/artifacts")
    public ResponseEntity<List<ArtifactView>> managedArtifacts(
            @PathVariable String taskId,
            HttpServletRequest request) {
        requireTask(request, taskId, false);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(managedExecutionService.artifacts(
                        taskId, actorUserId(request)));
    }

    @GetMapping("/{taskId}/managed-execution/artifacts/{artifactId}")
    public ResponseEntity<byte[]> managedArtifact(
            @PathVariable String taskId,
            @PathVariable String artifactId,
            HttpServletRequest request) {
        requireTask(request, taskId, false);
        ArtifactContent content = managedExecutionService.artifact(
                taskId, artifactId, actorUserId(request));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(content.mediaType()))
                .contentLength(content.bytes().length)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(content.filename(), StandardCharsets.UTF_8)
                                .build()
                                .toString())
                .header("X-ReachAI-Artifact-SHA256", content.sha256())
                .body(content.bytes());
    }

    @PostMapping("/{taskId}/managed-execution/approvals/{interactionId}:resolve")
    public ResponseEntity<ManagedExecutionDetailView> resolveManagedApproval(
            @PathVariable String taskId,
            @PathVariable String interactionId,
            @RequestBody ManagedApprovalDecisionCommand command,
            HttpServletRequest request) {
        requireTask(request, taskId, true);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(managedExecutionService.resolveApproval(
                        taskId, interactionId, command, actorUserId(request)));
    }

    @PostMapping("/{taskId}/handoffs")
    public ResponseEntity<HandoffPackageView> issueHandoff(
            @PathVariable String taskId,
            @RequestBody(required = false) HandoffIssueCommand command,
            HttpServletRequest request) {
        requireTask(request, taskId, true);
        return ResponseEntity.ok(handoffService.issue(
                taskId,
                AiCodingRequestSupport.publicBaseUrl(request),
                session(request).user().getUsername()));
    }

    @PostMapping("/{taskId}/questions/{questionId}/answer")
    public ResponseEntity<?> answerQuestion(
            @PathVariable String taskId,
            @PathVariable String questionId,
            @RequestBody AnswerQuestionCommand command,
            HttpServletRequest request) {
        requireTask(request, taskId, true);
        return ResponseEntity.ok(taskService.answerQuestion(taskId, questionId,
                new AnswerQuestionCommand(command == null ? null : command.answer(), session(request).user().getUsername())));
    }

    @PostMapping("/{taskId}/cancel")
    public ResponseEntity<TaskView> cancel(
            @PathVariable String taskId,
            @RequestBody(required = false) ActorCommand command,
            HttpServletRequest request) {
        requireTask(request, taskId, true);
        if (managedExecutionService.managed(taskId)) {
            return ResponseEntity.ok(managedExecutionService.cancel(
                    taskId, actorUserId(request)));
        }
        return ResponseEntity.ok(taskService.cancel(
                taskId,
                session(request).user().getUsername()));
    }

    @PostMapping("/{taskId}/acceptance")
    public ResponseEntity<TaskView> acceptance(
            @PathVariable String taskId,
            @RequestBody AcceptanceCommand command,
            HttpServletRequest request) {
        requireTask(request, taskId, true);
        return ResponseEntity.ok(taskService.finishAcceptance(
                taskId,
                command != null && command.passed(),
                command == null ? null : command.message(),
                session(request).user().getUsername()));
    }

    @PostMapping("/{taskId}/acceptance-verification")
    public ResponseEntity<AcceptanceVerificationView> verifyAcceptanceReadiness(
            @PathVariable String taskId,
            HttpServletRequest request) {
        requireTask(request, taskId, false);
        return ResponseEntity.ok(
                taskService.verifyAcceptanceReadiness(taskId));
    }

    public record ActorCommand(String actor) {
    }

    public record AcceptanceCommand(boolean passed, String message, String actor) {
    }

    /** The console route rule only admits the permission somewhere; each task is checked against its own project. */
    private TaskDetailView requireTask(HttpServletRequest request, String taskId, boolean write) {
        PlatformAuthenticatedSession session = session(request);
        TaskDetailView detail = taskService.detail(taskId);
        if (detail == null || detail.task() == null) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "task not found");
        }
        requireProject(session, detail.task().projectCode(), write);
        return detail;
    }

    private static void requireProject(PlatformAuthenticatedSession session, String projectCode, boolean write) {
        if (!allows(session, projectCode, write)) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,
                    "project permission is required");
        }
    }

    static boolean allows(PlatformAuthenticatedSession session, String projectCode, boolean write) {
        List<String> permissions = write
                ? List.of(PlatformPermissions.PLATFORM_WRITE, PlatformPermissions.WORKFLOW_WRITE)
                : List.of(PlatformPermissions.PLATFORM_READ, PlatformPermissions.WORKFLOW_READ);
        String code = projectCode == null || projectCode.isBlank() ? null : projectCode.trim();
        return permissions.stream().anyMatch(permission -> session.hasResourcePermission(permission, "PROJECT", null, code));
    }

    private PlatformAuthenticatedSession session(HttpServletRequest request) {
        Object candidate = request == null ? null : request.getAttribute(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE);
        if (!(candidate instanceof PlatformAuthenticatedSession session) || session.user() == null || session.user().getId() == null) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED,
                    "platform session is required");
        }
        return session;
    }

    private String actorUserId(HttpServletRequest request) {
        Object candidate = request == null
                ? null
                : request.getAttribute(
                        PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE);
        if (!(candidate instanceof PlatformAuthenticatedSession session)
                || session.user() == null
                || session.user().getId() == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.UNAUTHORIZED,
                    "platform session is required");
        }
        return String.valueOf(session.user().getId());
    }
}
