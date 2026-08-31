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
    public ResponseEntity<TaskView> create(@RequestBody CreateTaskCommand command) {
        return ResponseEntity.ok(taskService.create(command));
    }

    @GetMapping
    public ResponseEntity<List<TaskView>> list(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String projectCode,
            @RequestParam(required = false) String taskKind,
            @RequestParam(required = false) String executionStatus,
            @RequestParam(defaultValue = "100") int limit) {
        return ResponseEntity.ok(taskService.list(
                projectId,
                projectCode,
                taskKind,
                executionStatus,
                limit));
    }

    @GetMapping("/{taskId}")
    public ResponseEntity<TaskDetailView> detail(@PathVariable String taskId) {
        return ResponseEntity.ok(taskService.detail(taskId));
    }

    @PostMapping("/{taskId}/managed-execution")
    public ResponseEntity<ManagedExecutionDetailView> startManagedExecution(
            @PathVariable String taskId,
            @RequestBody(required = false) ManagedExecutionStartCommand command,
            HttpServletRequest request) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(managedExecutionService.start(
                        taskId, command, actorUserId(request)));
    }

    @GetMapping("/{taskId}/managed-execution")
    public ResponseEntity<ManagedExecutionDetailView> managedExecution(
            @PathVariable String taskId,
            HttpServletRequest request) {
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
        return ResponseEntity.ok(handoffService.issue(
                taskId,
                AiCodingRequestSupport.publicBaseUrl(request),
                command == null ? null : command.issuedBy()));
    }

    @PostMapping("/{taskId}/questions/{questionId}/answer")
    public ResponseEntity<?> answerQuestion(
            @PathVariable String taskId,
            @PathVariable String questionId,
            @RequestBody AnswerQuestionCommand command) {
        return ResponseEntity.ok(taskService.answerQuestion(taskId, questionId, command));
    }

    @PostMapping("/{taskId}/cancel")
    public ResponseEntity<TaskView> cancel(
            @PathVariable String taskId,
            @RequestBody(required = false) ActorCommand command,
            HttpServletRequest request) {
        if (managedExecutionService.managed(taskId)) {
            return ResponseEntity.ok(managedExecutionService.cancel(
                    taskId, actorUserId(request)));
        }
        return ResponseEntity.ok(taskService.cancel(
                taskId,
                command == null ? null : command.actor()));
    }

    @PostMapping("/{taskId}/acceptance")
    public ResponseEntity<TaskView> acceptance(
            @PathVariable String taskId,
            @RequestBody AcceptanceCommand command) {
        return ResponseEntity.ok(taskService.finishAcceptance(
                taskId,
                command != null && command.passed(),
                command == null ? null : command.message(),
                command == null ? null : command.actor()));
    }

    @PostMapping("/{taskId}/acceptance-verification")
    public ResponseEntity<AcceptanceVerificationView> verifyAcceptanceReadiness(
            @PathVariable String taskId) {
        return ResponseEntity.ok(
                taskService.verifyAcceptanceReadiness(taskId));
    }

    public record ActorCommand(String actor) {
    }

    public record AcceptanceCommand(boolean passed, String message, String actor) {
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
