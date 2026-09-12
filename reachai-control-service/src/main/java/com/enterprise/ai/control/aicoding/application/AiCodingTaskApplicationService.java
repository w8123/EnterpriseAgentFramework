package com.enterprise.ai.control.aicoding.application;

import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/** AI Coding 公开用例入口；命令保留事务，查询和响应投影交给所属查询组件。 */
@Service
@RequiredArgsConstructor
public class AiCodingTaskApplicationService {
    private final AiCodingTaskQueryService queries;
    private final AiCodingTaskDeliveryService delivery;
    private final AiCodingTaskCreationService creation;
    private final AiCodingTaskEventService protocolEvents;
    private final AiCodingTaskQuestionService questionCommands;

    @Transactional
    public TaskView create(CreateTaskCommand command) {
        return queries.toTaskView(creation.create(command));
    }

    @Transactional
    public VerificationView requestVerification(String taskId, String verificationKey) {
        return delivery.requestVerification(taskId, verificationKey);
    }

    @Transactional
    public TaskView recordEvent(String taskId, EventCommand command) {
        return queries.toTaskView(protocolEvents.recordEvent(taskId, command));
    }

    @Transactional
    public QuestionView askQuestion(String taskId, AskQuestionCommand command) {
        return queries.toQuestionView(questionCommands.askQuestion(taskId, command));
    }

    @Transactional
    public QuestionView answerQuestion(String taskId, String questionId, AnswerQuestionCommand command) {
        return queries.toQuestionView(questionCommands.answerQuestion(taskId, questionId, command));
    }

    @Transactional
    public ArtifactApplyView submitArtifact(String taskId, ArtifactEnvelope envelope) {
        var outcome = delivery.submitArtifact(taskId, envelope);
        return new ArtifactApplyView(queries.toTaskView(outcome.task()), queries.toArtifactView(outcome.artifact()), outcome.applicationResult());
    }

    @Transactional
    public AcceptanceVerificationView verifyAcceptanceReadiness(String taskId) {
        var outcome = delivery.verifyAcceptanceReadiness(taskId);
        var gate = outcome.gate();
        return new AcceptanceVerificationView(queries.toTaskView(outcome.task()), gate.readiness(), gate.ready(), gate.blockers());
    }

    @Transactional
    public TaskView finishAcceptance(String taskId, boolean passed, String message, String actor) {
        return queries.toTaskView(delivery.finishAcceptance(taskId, passed, message, actor));
    }

    @Transactional
    public TaskView cancel(String taskId, String actor) {
        return queries.toTaskView(protocolEvents.cancel(taskId, actor));
    }


    public List<TaskView> list(Long projectId, String projectCode, String taskKind, String executionStatus, int limit) {
        return queries.list(projectId, projectCode, taskKind, executionStatus, limit);
    }

    public LatestAppliedTaskArtifact latestAppliedTaskArtifact(String projectCode, String taskKind, int limit) {
        return queries.latestAppliedTaskArtifact(projectCode, taskKind, limit);
    }

    public LatestAppliedTaskArtifact latestAppliedTaskArtifact(List<TaskView> tasks, String taskKind, int limit) {
        return queries.latestAppliedTaskArtifact(tasks, taskKind, limit);
    }

    public TaskDetailView detail(String taskId) {
        return queries.detail(taskId);
    }

    public TaskContextView context(String taskId, String publicBaseUrl) {
        return queries.context(taskId, publicBaseUrl);
    }

    public List<TaskEventView> events(String taskId, Long afterEventId) {
        return queries.events(taskId, afterEventId);
    }

    public List<QuestionView> questions(String taskId, LocalDateTime updatedAfter) {
        return queries.questions(taskId, updatedAfter);
    }

    public List<ArtifactView> artifacts(String taskId) {
        return queries.artifacts(taskId);
    }

    public TaskView task(String taskId) {
        return queries.task(taskId);
    }

    public TaskDescriptor descriptor(String taskId) {
        return queries.descriptor(taskId);
    }
}
