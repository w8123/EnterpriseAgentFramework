package com.enterprise.ai.control.aicoding.api;

import com.enterprise.ai.control.aicoding.application.AiCodingHandoffApplicationService;
import com.enterprise.ai.control.aicoding.application.AiCodingTaskApplicationService;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.AnswerQuestionCommand;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.AcceptanceVerificationView;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.CreateTaskCommand;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.HandoffIssueCommand;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.HandoffPackageView;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskDetailView;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskView;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/ai-coding-console/tasks")
@RequiredArgsConstructor
public class AiCodingTaskConsoleController {

    private final AiCodingTaskApplicationService taskService;
    private final AiCodingHandoffApplicationService handoffService;

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
            @RequestBody(required = false) ActorCommand command) {
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
}
