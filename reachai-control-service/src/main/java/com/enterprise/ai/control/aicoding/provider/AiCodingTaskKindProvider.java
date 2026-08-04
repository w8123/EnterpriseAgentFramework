package com.enterprise.ai.control.aicoding.provider;

import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ArtifactApplyResult;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ArtifactEnvelope;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ReadinessItem;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskContract;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskDescriptor;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

public interface AiCodingTaskKindProvider {

    String kind();

    TaskContract contract();

    JsonNode buildContext(TaskDescriptor task);

    default JsonNode materializeContext(
            TaskDescriptor task,
            JsonNode contextSnapshot,
            String publicBaseUrl) {
        return contextSnapshot;
    }

    List<ReadinessItem> readiness(TaskDescriptor task);

    default List<ReadinessItem> readiness(
            TaskDescriptor task,
            JsonNode applicationResult) {
        return readiness(task);
    }

    default List<String> acceptanceReadinessKeys() {
        return List.of();
    }

    default JsonNode requestVerification(
            TaskDescriptor task,
            String verificationKey) {
        throw new IllegalArgumentException(
                "unsupported verificationKey for " + kind() + ": "
                        + verificationKey);
    }

    ArtifactApplyResult applyArtifact(TaskDescriptor task, ArtifactEnvelope artifact);
}
