package com.enterprise.ai.control.aicoding.application;

import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ArtifactIdempotencyPolicy;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskCompletionPolicy;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskContract;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskEventStateRule;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskProtocolGuide;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskProtocolInputLimits;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskValues;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskValues.ExecutionStatus;
import com.enterprise.ai.control.aicoding.security.AiCodingSensitiveJsonSanitizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/** 客户端协议说明与示例的组装，不承担任务状态迁移。 */
final class AiCodingTaskProtocolGuideFactory {

    private AiCodingTaskProtocolGuideFactory() {
    }

    static TaskProtocolGuide create(
            ObjectMapper objectMapper,
            AiCodingSensitiveJsonSanitizer sanitizer,
            String executorProvider,
            TaskContract contract) {
        ObjectNode started = protocolEventExample(
                objectMapper,
                executorProvider,
                "STARTED",
                "已获取 ReachAI context，开始执行任务");
        ObjectNode progress = protocolEventExample(
                objectMapper,
                executorProvider,
                "PROGRESS",
                "已完成当前阶段，继续执行下一阶段");

        ObjectNode question = objectMapper.createObjectNode();
        question.put("schema", AiCodingTaskValues.QUESTION_SCHEMA);
        question.put("clientEventId", "replace-with-stable-guid");
        question.put("questionId", "replace-with-stable-question-id");
        question.put("title", "需要确认的问题");
        question.put("body", "请提供完成当前任务所需的业务信息");
        question.putArray("options");
        question.put("askedBy", executorProvider);

        ObjectNode artifact = objectMapper.createObjectNode();
        artifact.put("schema", AiCodingTaskValues.ARTIFACT_SCHEMA);
        artifact.put("clientEventId", "replace-with-stable-guid");
        artifact.put("artifactKey", "replace-with-stable-artifact-key");
        artifact.putObject("contract")
                .put("key", contract.resultContractKey())
                .put("version", contract.resultContractVersion());
        artifact.set("content", sanitizer.sanitize(contract.example()));
        artifact.putObject("reportedBy")
                .put("provider", executorProvider)
                .put("sessionRef", "optional-client-session-reference");

        return new TaskProtocolGuide(
                "application/json; charset=utf-8",
                List.of("STARTED", "PROGRESS", "RESUMED", "FAILED"),
                List.of(
                        new TaskEventStateRule(
                                "STARTED",
                                List.of("READY", "RUNNING"),
                                "TRANSITION_TO_RUNNING",
                                null),
                        new TaskEventStateRule(
                                "PROGRESS",
                                List.of("RUNNING", "WAITING_USER"),
                                "PRESERVE_CURRENT_STATUS",
                                "WAITING_USER progress does not answer questions or resume the task"),
                        new TaskEventStateRule(
                                "RESUMED",
                                List.of("WAITING_USER"),
                                "TRANSITION_TO_RUNNING",
                                "All task questions must be answered and read by the client"),
                        new TaskEventStateRule(
                                "FAILED",
                                List.of(
                                        "READY",
                                        "RUNNING",
                                        "WAITING_USER",
                                        "RESULT_SUBMITTED",
                                        "RESULT_APPLIED"),
                                "TRANSITION_TO_FAILED",
                                null)),
                started,
                progress,
                question,
                artifact,
                new TaskProtocolInputLimits(
                        AiCodingTaskValues.CLIENT_EVENT_ID_MAX_CHARACTERS,
                        AiCodingTaskValues.EVENT_MESSAGE_MAX_CHARACTERS,
                        AiCodingTaskValues.QUESTION_ID_MAX_CHARACTERS,
                        AiCodingTaskValues.QUESTION_TITLE_MAX_CHARACTERS,
                        AiCodingTaskValues.QUESTION_OPTION_MAX_CHARACTERS,
                        AiCodingTaskValues.QUESTION_OPTION_MAX_COUNT,
                        AiCodingTaskValues.TEXT_MAX_UTF8_BYTES,
                        AiCodingTaskValues.ARTIFACT_KEY_MAX_CHARACTERS,
                        AiCodingTaskValues.CLIENT_SESSION_REF_MAX_CHARACTERS),
                new ArtifactIdempotencyPolicy(
                        "TASK_AND_ARTIFACT_KEY",
                        true,
                        true,
                        "result-v2"),
                new TaskCompletionPolicy(
                        false,
                        "POST_ARTIFACT",
                        ExecutionStatus.RESULT_SUBMITTED.name(),
                        ExecutionStatus.ACCEPTANCE_READY.name(),
                        ExecutionStatus.COMPLETED.name(),
                        "task.executionStatus"));
    }

    private static ObjectNode protocolEventExample(
            ObjectMapper objectMapper,
            String executorProvider,
            String eventType,
            String message) {
        ObjectNode event = objectMapper.createObjectNode();
        event.put("schema", AiCodingTaskValues.EVENT_SCHEMA);
        event.put("clientEventId", "replace-with-stable-guid");
        event.put("eventType", eventType);
        event.put("message", message);
        event.putObject("payload").put("stepKey", "replace-with-current-step");
        event.put("reportedBy", executorProvider);
        return event;
    }

}
