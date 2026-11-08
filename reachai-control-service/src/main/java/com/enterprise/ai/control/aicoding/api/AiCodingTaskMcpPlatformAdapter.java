package com.enterprise.ai.control.aicoding.api;

import com.enterprise.ai.control.aicoding.application.AiCodingConnectionClaimService;
import com.enterprise.ai.control.aicoding.application.AiCodingTaskApplicationService;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.AnswerQuestionCommand;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ArtifactEnvelope;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.AskQuestionCommand;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.CreateTaskCommand;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.EventCommand;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskValues;
import com.enterprise.ai.control.aicoding.security.AiCodingTaskTokenGuard;
import com.enterprise.ai.control.identity.PlatformAiConnectionService.ConnectionIdentity;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformDelegatedRequestScope;
import com.enterprise.ai.control.mcp.application.port.McpPlatformAiCodingPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/** Runs AI coding task calls through the console controller with the verified actor. */
@Component
@RequiredArgsConstructor
class AiCodingTaskMcpPlatformAdapter implements McpPlatformAiCodingPort {
    private final AiCodingTaskConsoleController console;
    private final PlatformDelegatedRequestScope scope;
    private final ObjectMapper json;
    private final AiCodingConnectionClaimService claims;
    private final AiCodingTaskApplicationService tasks;
    private final AiCodingTaskTokenGuard guard;

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

    @Override
    public ResponseEntity<?> claim(ConnectionIdentity identity, String taskId, String claimCode) {
        return scope.run(identity.session(), request -> refusals(() -> {
            console.requireTaskAccess(request, taskId, true);
            return ResponseEntity.ok(claims.claim(taskId, claimCode, identity, AiCodingRequestSupport.publicBaseUrl(request)));
        }));
    }

    @Override
    public ResponseEntity<?> claimAsCreator(ConnectionIdentity identity, String taskId) {
        return scope.run(identity.session(), request -> refusals(() -> {
            console.requireTaskAccess(request, taskId, true);
            return ResponseEntity.ok(claims.claimAsCreator(taskId, identity, AiCodingRequestSupport.publicBaseUrl(request)));
        }));
    }

    @Override
    public ResponseEntity<?> agent(ConnectionIdentity identity, String taskId, Agent step, Map<String, Object> body) {
        return scope.run(identity.session(), request -> refusals(() -> {
            // The person may have lost the project since claiming; check both the project and the claim every time.
            console.requireTaskAccess(request, taskId, true);
            guard.requireConnectionTaskAccess(taskId, identity.connectionId());
            Map<String, Object> command = withSchema(step, body);
            Object result = switch (step) {
                case CONTEXT -> tasks.context(taskId, AiCodingRequestSupport.publicBaseUrl(request));
                case HEARTBEAT -> tasks.task(taskId).connection();
                case EVENTS -> tasks.events(taskId, command.get("afterEventId") instanceof Number number ? number.longValue() : null);
                case EVENT -> tasks.recordEvent(taskId, json.convertValue(command, EventCommand.class));
                case QUESTIONS -> tasks.questions(taskId, command.get("updatedAfter") instanceof String text && !text.isBlank()
                        ? LocalDateTime.parse(text) : null);
                case ASK -> tasks.askQuestion(taskId, json.convertValue(command, AskQuestionCommand.class));
                case ARTIFACT -> tasks.submitArtifact(taskId, json.convertValue(command, ArtifactEnvelope.class));
                case VERIFY -> tasks.requestVerification(taskId, String.valueOf(command.get("verificationKey")));
            };
            return ResponseEntity.ok(result);
        }));
    }

    /**
     * Task refusals are written by ReachAI for the coding agent (wrong code, expired claim, artifact checks), so they
     * travel back as an {@code MCP_AI_CODING_*} code with the message instead of a bare HTTP status.
     */
    private static ResponseEntity<?> refusals(Supplier<ResponseEntity<?>> action) {
        try {
            return action.get();
        } catch (ResponseStatusException failure) {
            int status = failure.getStatusCode().value();
            if (status == HttpStatus.FORBIDDEN.value() && failure.getReason() != null
                    && failure.getReason().startsWith("project ")) {
                throw failure;
            }
            String code = switch (status) {
                case 401 -> "MCP_AI_CODING_CLAIM_CODE_INVALID";
                case 403 -> "MCP_AI_CODING_TASK_NOT_CLAIMED";
                case 404 -> "MCP_AI_CODING_TASK_NOT_FOUND";
                case 409 -> "MCP_AI_CODING_TASK_CONFLICT";
                case 410 -> "MCP_AI_CODING_CLAIM_EXPIRED";
                case 429 -> "MCP_AI_CODING_CLAIM_ATTEMPTS_EXCEEDED";
                default -> "MCP_AI_CODING_TASK_REJECTED";
            };
            return refusal(status, code, failure.getReason());
        } catch (IllegalArgumentException failure) {
            return refusal(400, "MCP_AI_CODING_INVALID_REQUEST", failure.getMessage());
        } catch (IllegalStateException failure) {
            return refusal(409, "MCP_AI_CODING_TASK_CONFLICT", failure.getMessage());
        }
    }

    /** The connection speaks for the task's own executor; schemas are filled in so the agent only sends content. */
    private static Map<String, Object> withSchema(Agent step, Map<String, Object> body) {
        Map<String, Object> command = new LinkedHashMap<>(body == null ? Map.of() : body);
        String schema = switch (step) {
            case EVENT -> AiCodingTaskValues.EVENT_SCHEMA;
            case ASK -> AiCodingTaskValues.QUESTION_SCHEMA;
            case ARTIFACT -> AiCodingTaskValues.ARTIFACT_SCHEMA;
            default -> null;
        };
        if (schema != null) command.putIfAbsent("schema", schema);
        return command;
    }

    private static ResponseEntity<?> refusal(int status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        if (message != null) body.put("message", message.length() > 1000 ? message.substring(0, 1000) : message);
        return ResponseEntity.status(status).body(body);
    }
}
