package com.enterprise.ai.control.aicoding.api;

import com.enterprise.ai.control.aicoding.application.AiCodingTaskApplicationService;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ArtifactApplyView;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ArtifactEnvelope;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.AskQuestionCommand;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ConnectionView;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.EventCommand;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.QuestionView;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskContextView;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskEventView;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskView;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.VerificationView;
import com.enterprise.ai.control.aicoding.security.AiCodingTaskTokenGuard;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping(
        value = "/api/ai-coding/tasks/{taskId}",
        produces = AiCodingRequestSupport.JSON_UTF8_VALUE)
@RequiredArgsConstructor
public class AiCodingTaskProtocolController {

    private final AiCodingTaskApplicationService taskService;
    private final AiCodingTaskTokenGuard tokenGuard;

    @GetMapping("/context")
    public ResponseEntity<TaskContextView> context(
            @PathVariable String taskId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
            String authorization,
            HttpServletRequest request) {
        tokenGuard.requireTaskAccess(taskId, authorization);
        return ResponseEntity.ok(taskService.context(
                taskId,
                AiCodingRequestSupport.publicBaseUrl(request)));
    }

    @PostMapping("/heartbeat")
    public ResponseEntity<ConnectionView> heartbeat(
            @PathVariable String taskId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
            String authorization) {
        tokenGuard.requireTaskAccess(taskId, authorization);
        return ResponseEntity.ok(taskService.task(taskId).connection());
    }

    @GetMapping("/events")
    public ResponseEntity<List<TaskEventView>> events(
            @PathVariable String taskId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
            String authorization,
            @RequestParam(required = false) Long afterEventId) {
        tokenGuard.requireTaskAccess(taskId, authorization);
        return ResponseEntity.ok(taskService.events(taskId, afterEventId));
    }

    @PostMapping("/events")
    public ResponseEntity<TaskView> event(
            @PathVariable String taskId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
            String authorization,
            @RequestBody EventCommand command) {
        tokenGuard.requireTaskAccess(taskId, authorization);
        return ResponseEntity.ok(taskService.recordEvent(taskId, command));
    }

    @GetMapping("/questions")
    public ResponseEntity<List<QuestionView>> questions(
            @PathVariable String taskId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
            String authorization,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            LocalDateTime updatedAfter) {
        tokenGuard.requireTaskAccess(taskId, authorization);
        return ResponseEntity.ok(taskService.questions(taskId, updatedAfter));
    }

    @PostMapping("/questions")
    public ResponseEntity<QuestionView> question(
            @PathVariable String taskId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
            String authorization,
            @RequestBody AskQuestionCommand command) {
        tokenGuard.requireTaskAccess(taskId, authorization);
        return ResponseEntity.ok(taskService.askQuestion(taskId, command));
    }

    @PostMapping("/artifacts")
    public ResponseEntity<ArtifactApplyView> artifact(
            @PathVariable String taskId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
            String authorization,
            @RequestBody ArtifactEnvelope envelope) {
        tokenGuard.requireTaskAccess(taskId, authorization);
        return ResponseEntity.ok(taskService.submitArtifact(taskId, envelope));
    }

    @PostMapping("/verifications/{verificationKey}")
    public ResponseEntity<VerificationView> verification(
            @PathVariable String taskId,
            @PathVariable String verificationKey,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
            String authorization) {
        tokenGuard.requireTaskAccess(taskId, authorization);
        return ResponseEntity.ok(taskService.requestVerification(
                taskId,
                verificationKey));
    }
}
