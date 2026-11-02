package com.enterprise.ai.control.aicoding.api;

import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.AnswerQuestionCommand;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.CreateTaskCommand;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformDelegatedRequestScope;
import com.enterprise.ai.control.mcp.application.port.McpPlatformAiCodingPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Runs AI coding task calls through the console controller with the verified actor. */
@Component
@RequiredArgsConstructor
class AiCodingTaskMcpPlatformAdapter implements McpPlatformAiCodingPort {
    private final AiCodingTaskConsoleController console;
    private final PlatformDelegatedRequestScope scope;
    private final ObjectMapper json;

    @Override
    public ResponseEntity<?> list(PlatformAuthenticatedSession actor, Long projectId, String projectCode, String taskKind,
                                  String executionStatus, int limit) {
        return scope.run(actor, request -> console.list(projectId, projectCode, taskKind, executionStatus, limit, request));
    }

    @Override
    public ResponseEntity<?> read(PlatformAuthenticatedSession actor, String taskId, Read read) {
        return scope.run(actor, request -> switch (read) {
            case DETAIL -> console.detail(taskId, request);
            case MANAGED_EXECUTION -> console.managedExecution(taskId, request);
            case ARTIFACTS -> console.managedArtifacts(taskId, request);
            case ACCEPTANCE_READINESS -> console.verifyAcceptanceReadiness(taskId, request);
        });
    }

    @Override
    public ResponseEntity<?> create(PlatformAuthenticatedSession actor, Map<String, Object> command) {
        return scope.run(actor, request -> console.create(json.convertValue(command, CreateTaskCommand.class), request));
    }

    @Override
    public ResponseEntity<?> cancel(PlatformAuthenticatedSession actor, String taskId) {
        return scope.run(actor, request -> console.cancel(taskId, null, request));
    }

    @Override
    public ResponseEntity<?> answer(PlatformAuthenticatedSession actor, String taskId, String questionId, String answer) {
        return scope.run(actor, request -> console.answerQuestion(taskId, questionId, new AnswerQuestionCommand(answer, null), request));
    }
}
