package com.enterprise.ai.control.aicoding.application;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.VerificationView;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ArtifactApplyResult;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ArtifactEnvelope;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ArtifactReporter;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ReadinessItem;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskDescriptor;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskValues.ActorType;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskValues.ArtifactNextAction;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskValues.ArtifactProcessingStatus;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskValues.ExecutionStatus;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskValues.ExecutorProvider;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskValues;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskArtifactEntity;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskArtifactMapper;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskEntity;
import com.enterprise.ai.control.aicoding.provider.AiCodingTaskKindProvider;
import com.enterprise.ai.control.aicoding.provider.AiCodingTaskProviderRegistry;
import com.enterprise.ai.control.aicoding.security.AiCodingSensitiveJsonSanitizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Artifact 提交、Provider 验证与应用和平台验收命令；任务状态写入统一交给 StateChanges。 */
@Component
@RequiredArgsConstructor
class AiCodingTaskDeliveryService {
    private final AiCodingTaskArtifactMapper artifactMapper;
    private final AiCodingTaskProviderRegistry providerRegistry;
    private final AiCodingArtifactProviderExecutor providerExecutor;
    private final AiCodingHandoffApplicationService handoffService;
    private final AiCodingSensitiveJsonSanitizer sanitizer;
    private final ObjectMapper objectMapper;
    private final AiCodingTaskJsonSupport json;
    private final AiCodingTaskStateChanges stateChanges;
    private final AiCodingTaskDescriptorReader readModels;

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public VerificationView requestVerification(
            String taskId,
            String verificationKey) {
        AiCodingTaskEntity task = stateChanges.requireTask(taskId);
        stateChanges.requireStatus(task, ExecutionStatus.RUNNING);
        String normalizedKey = AiCodingTaskValues.requiredKey(
                verificationKey,
                "verificationKey");
        AiCodingTaskKindProvider provider =
                providerRegistry.require(task.getTaskKind());
        JsonNode result = sanitizer.sanitize(provider.requestVerification(
                readModels.descriptor(task),
                normalizedKey));
        String message = "AI Coding verification completed: " + normalizedKey;
        task.setLastMessage(message);
        task.setUpdatedAt(LocalDateTime.now());
        stateChanges.requireUpdated(task);
        stateChanges.appendEvent(
                task,
                null,
                "VERIFICATION_COMPLETED",
                message,
                result,
                ActorType.AI_CODING,
                task.getExecutorProvider());
        return new VerificationView(
                "reachai.ai-coding.verification.v1",
                normalizedKey,
                "PASS",
                message,
                result);
    }

    @Transactional
    public ArtifactOutcome submitArtifact(String taskId, ArtifactEnvelope envelope) {
        if (envelope == null) {
            throw new IllegalArgumentException("artifact request is required");
        }
        AiCodingTaskValues.requireSchema(
                envelope.schema(),
                AiCodingTaskValues.ARTIFACT_SCHEMA,
                "artifact.schema");
        String clientEventId = AiCodingTaskValues.requiredText(
                envelope.clientEventId(),
                "clientEventId",
                AiCodingTaskValues.CLIENT_EVENT_ID_MAX_CHARACTERS);
        String artifactKey = AiCodingTaskValues.requiredText(
                envelope.artifactKey(),
                "artifactKey",
                AiCodingTaskValues.ARTIFACT_KEY_MAX_CHARACTERS);
        if (envelope.contract() == null) {
            throw new IllegalArgumentException("artifact.contract is required");
        }
        if (envelope.content() == null || envelope.content().isNull()) {
            throw new IllegalArgumentException("artifact.content is required");
        }

        AiCodingTaskEntity task = stateChanges.requireTask(taskId);
        ArtifactReporter reporter = normalizeArtifactReporter(task, envelope.reportedBy());
        JsonNode sanitizedContent = sanitizer.sanitize(envelope.content());
        ArtifactEnvelope safeEnvelope = new ArtifactEnvelope(
                envelope.schema(),
                clientEventId,
                artifactKey,
                envelope.contract(),
                sanitizedContent,
                reporter);

        requireContract(task, safeEnvelope);
        AiCodingTaskArtifactEntity duplicate = findArtifact(taskId, artifactKey);
        String contentJson = json.canonicalJson(sanitizedContent);
        String contentHash = AiCodingTaskJsonSupport.sha256(contentJson);
        if (duplicate != null) {
            if (!contentHash.equals(duplicate.getContentHash())) {
                throw new IllegalStateException(
                        "artifactKey was already submitted with different content");
            }
            return new ArtifactOutcome(
                    task,
                    duplicate,
                    json.readJsonOrNull(duplicate.getApplicationResultJson()));
        }
        stateChanges.requireStatus(task, ExecutionStatus.RUNNING);
        AiCodingTaskValues.requiredText(clientEventId, "clientEventId");
        if (stateChanges.findClientEvent(taskId, clientEventId) != null) {
            throw new IllegalStateException(
                    "clientEventId is already used without this artifactKey");
        }

        LocalDateTime now = LocalDateTime.now();
        AiCodingTaskArtifactEntity artifact = new AiCodingTaskArtifactEntity();
        artifact.setTaskId(taskId);
        artifact.setArtifactKey(artifactKey);
        artifact.setContractKey(envelope.contract().key());
        artifact.setContractVersion(envelope.contract().version());
        artifact.setContentHash(contentHash);
        artifact.setContentJson(contentJson);
        artifact.setProcessingStatus(ArtifactProcessingStatus.RECEIVED.name());
        artifact.setReportedBy(reporter.provider());
        artifact.setCreatedAt(now);
        try {
            artifactMapper.insert(artifact);
        } catch (DuplicateKeyException ex) {
            AiCodingTaskArtifactEntity raced = findArtifact(taskId, artifactKey);
            if (raced != null && contentHash.equals(raced.getContentHash())) {
                return new ArtifactOutcome(
                        stateChanges.requireTask(taskId),
                        raced,
                        json.readJsonOrNull(raced.getApplicationResultJson()));
            }
            throw new IllegalStateException(
                    "artifactKey was concurrently submitted with different content",
                    ex);
        }

        stateChanges.transition(
                task,
                ExecutionStatus.RESULT_SUBMITTED,
                clientEventId,
                "RESULT_SUBMITTED",
                task.getExecutorProvider() + " 已提交 Artifact，等待 ReachAI 校验",
                null,
                ActorType.AI_CODING,
                artifact.getReportedBy());

        AiCodingTaskKindProvider provider = providerRegistry.require(task.getTaskKind());
        ArtifactApplyResult result = providerExecutor.apply(
                provider,
                readModels.descriptor(task),
                safeEnvelope);
        artifact.setValidatedAt(LocalDateTime.now());
        artifact.setApplicationResultJson(
                result.domainResult() == null ? null : json.writeJson(result.domainResult()));
        if (!result.applied()) {
            artifact.setProcessingStatus(ArtifactProcessingStatus.REJECTED.name());
            artifact.setValidationMessage(AiCodingTaskValues.fitText(
                    AiCodingTaskValues.firstText(result.message(), "Artifact validation failed"),
                    AiCodingTaskValues.EVENT_MESSAGE_MAX_CHARACTERS));
            artifactMapper.updateById(artifact);
            stateChanges.transition(
                    task,
                    ExecutionStatus.RUNNING,
                    null,
                    "ARTIFACT_REJECTED",
                    artifact.getValidationMessage(),
                    null,
                    ActorType.REACHAI,
                    "task-kernel");
            return new ArtifactOutcome(
                    task,
                    artifact,
                    result.domainResult());
        }

        artifact.setProcessingStatus(ArtifactProcessingStatus.APPLIED.name());
        artifact.setValidationMessage(AiCodingTaskValues.fitText(
                AiCodingTaskValues.optionalText(result.message()),
                AiCodingTaskValues.EVENT_MESSAGE_MAX_CHARACTERS));
        artifact.setAppliedAt(LocalDateTime.now());
        artifactMapper.updateById(artifact);
        stateChanges.transition(
                task,
                ExecutionStatus.RESULT_APPLIED,
                null,
                "RESULT_APPLIED",
                AiCodingTaskValues.firstText(result.message(), "Artifact 已校验并应用"),
                null,
                ActorType.REACHAI,
                "task-kernel");

        ArtifactNextAction nextAction = result.nextAction() == null
                ? ArtifactNextAction.STAY_APPLIED
                : result.nextAction();
        if (nextAction == ArtifactNextAction.COMPLETE) {
            stateChanges.transition(
                    task,
                    ExecutionStatus.COMPLETED,
                    null,
                    "COMPLETED",
                    AiCodingTaskValues.firstText(result.message(), "AI Coding 任务已完成"),
                    null,
                    ActorType.REACHAI,
                    "task-kernel");
            handoffService.closeTaskHandoffs(taskId, "TASK_COMPLETED");
        } else if (nextAction == ArtifactNextAction.FAIL) {
            stateChanges.transition(
                    task,
                    ExecutionStatus.FAILED,
                    null,
                    "FAILED",
                    AiCodingTaskValues.firstText(result.message(), "AI Coding 任务结果未通过"),
                    result.domainResult(),
                    ActorType.REACHAI,
                    "task-kernel");
            handoffService.closeTaskHandoffs(taskId, "TASK_FAILED");
        } else if (nextAction == ArtifactNextAction.ACCEPTANCE_REQUIRED) {
            advanceToAcceptanceReadyIfVerified(
                    task,
                    provider,
                    result.domainResult());
        }
        return new ArtifactOutcome(
                task,
                artifact,
                result.domainResult());
    }

    @Transactional
    AcceptanceOutcome verifyAcceptanceReadiness(String taskId) {
        AiCodingTaskEntity task = stateChanges.requireTask(taskId);
        AiCodingTaskKindProvider provider = providerRegistry.require(task.getTaskKind());
        if (provider.acceptanceReadinessKeys() == null || provider.acceptanceReadinessKeys().isEmpty()) {
            throw new IllegalStateException("task kind does not define platform acceptance-readiness gates");
        }
        AcceptanceGate gate;
        if (AiCodingTaskStateChanges.status(task) == ExecutionStatus.ACCEPTANCE_READY) {
            gate = evaluateAcceptanceGate(provider, readModels.descriptor(task), latestAppliedApplicationResult(taskId));
        } else {
            stateChanges.requireStatus(task, ExecutionStatus.RESULT_APPLIED);
            gate = advanceToAcceptanceReadyIfVerified(task, provider, latestAppliedApplicationResult(taskId));
        }
        return new AcceptanceOutcome(task, gate);
    }

    @Transactional
    AiCodingTaskEntity finishAcceptance(
            String taskId,
            boolean passed,
            String message,
            String actor) {
        AiCodingTaskEntity task = stateChanges.requireTask(taskId);
        stateChanges.requireStatus(task, ExecutionStatus.ACCEPTANCE_READY);
        AiCodingTaskKindProvider provider = providerRegistry.require(task.getTaskKind());
        AcceptanceGate gate = evaluateAcceptanceGate(
                provider,
                readModels.descriptor(task),
                latestAppliedApplicationResult(taskId));
        if (!gate.ready()) {
            throw new IllegalStateException(
                    "platform acceptance-readiness gates are not PASS: "
                            + String.join("; ", gate.blockers()));
        }
        stateChanges.transition(
                task,
                passed ? ExecutionStatus.COMPLETED : ExecutionStatus.FAILED,
                null,
                passed ? "ACCEPTANCE_PASSED" : "ACCEPTANCE_FAILED",
                AiCodingTaskValues.requiredText(
                        message,
                        "message",
                        AiCodingTaskValues.EVENT_MESSAGE_MAX_CHARACTERS),
                null,
                ActorType.REACHAI,
                AiCodingTaskValues.optionalText(
                        actor,
                        "actor",
                        AiCodingTaskValues.ACTOR_NAME_MAX_CHARACTERS));
        handoffService.closeTaskHandoffs(
                taskId,
                passed ? "TASK_COMPLETED" : "TASK_FAILED");
        return task;
    }

    private AcceptanceGate advanceToAcceptanceReadyIfVerified(
            AiCodingTaskEntity task,
            AiCodingTaskKindProvider provider,
            JsonNode applicationResult) {
        AcceptanceGate gate = evaluateAcceptanceGate(
                provider,
                readModels.descriptor(task),
                applicationResult);
        ObjectNode payload = objectMapper.createObjectNode();
        payload.set(
                "requiredReadinessKeys",
                objectMapper.valueToTree(gate.requiredKeys()));
        payload.set("readiness", objectMapper.valueToTree(gate.readiness()));
        payload.set("blockers", objectMapper.valueToTree(gate.blockers()));
        if (gate.ready()) {
            stateChanges.transition(
                    task,
                    ExecutionStatus.ACCEPTANCE_READY,
                    null,
                    "ACCEPTANCE_READY",
                    "ReachAI 平台验证已通过，等待用户验收",
                    payload,
                    ActorType.REACHAI,
                    "task-kernel");
            handoffService.closeTaskHandoffs(
                    task.getTaskId(),
                    "TASK_ACCEPTANCE_READY");
            return gate;
        }

        String message = AiCodingTaskValues.fitText(
                "结果已回传，但尚未通过平台验证："
                        + String.join("；", gate.blockers()),
                AiCodingTaskValues.EVENT_MESSAGE_MAX_CHARACTERS);
        if (message.equals(task.getLastMessage())) {
            return gate;
        }
        task.setLastMessage(message);
        task.setUpdatedAt(LocalDateTime.now());
        stateChanges.requireUpdated(task);
        stateChanges.appendEvent(
                task,
                null,
                "ACCEPTANCE_BLOCKED",
                message,
                payload,
                ActorType.REACHAI,
                "task-kernel");
        return gate;
    }

    private AcceptanceGate evaluateAcceptanceGate(
            AiCodingTaskKindProvider provider,
            TaskDescriptor descriptor,
            JsonNode applicationResult) {
        List<String> requiredKeys = provider.acceptanceReadinessKeys() == null
                ? List.of()
                : provider.acceptanceReadinessKeys().stream()
                .filter(StringUtils::hasText)
                .map(key -> key.trim().toUpperCase(Locale.ROOT))
                .distinct()
                .toList();
        List<ReadinessItem> readiness = readiness(
                provider,
                descriptor,
                applicationResult);
        if (requiredKeys.isEmpty()) {
            return new AcceptanceGate(requiredKeys, readiness, true, List.of());
        }

        Map<String, List<ReadinessItem>> byKey = new LinkedHashMap<>();
        for (ReadinessItem item : readiness) {
            if (item != null && StringUtils.hasText(item.key())) {
                byKey.computeIfAbsent(item.key().trim().toUpperCase(Locale.ROOT),
                        ignored -> new ArrayList<>()).add(item);
            }
        }
        List<String> blockers = new ArrayList<>();
        for (String key : requiredKeys) {
            List<ReadinessItem> observations = byKey.get(key);
            if (observations == null) {
                blockers.add(key + " 未返回平台验证结果");
                continue;
            }
            for (ReadinessItem item : observations) {
                // 同一门禁的任何非 PASS 结果都必须保留，不能被后返回的 PASS 覆盖。
                String itemStatus = StringUtils.hasText(item.status())
                        ? item.status().trim().toUpperCase(Locale.ROOT)
                        : "UNKNOWN";
                if (!"PASS".equals(itemStatus)) {
                    blockers.add(AiCodingTaskValues.firstText(item.label(), key)
                            + "（" + itemStatus + "）");
                }
            }
        }
        return new AcceptanceGate(
                requiredKeys,
                readiness,
                blockers.isEmpty(),
                List.copyOf(blockers));
    }

    List<ReadinessItem> readiness(
            AiCodingTaskKindProvider provider,
            TaskDescriptor descriptor,
            JsonNode applicationResult) {
        List<ReadinessItem> source = provider.readiness(
                descriptor,
                applicationResult);
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        return source.stream()
                .filter(Objects::nonNull)
                .map(item -> new ReadinessItem(
                        item.key(),
                        item.label(),
                        item.status(),
                        item.message(),
                        sanitizer.sanitize(item.evidence())))
                .toList();
    }

    private AiCodingTaskArtifactEntity findArtifact(
            String taskId,
            String artifactKey) {
        return artifactMapper.selectOne(
                Wrappers.<AiCodingTaskArtifactEntity>lambdaQuery()
                        .eq(AiCodingTaskArtifactEntity::getTaskId, taskId)
                        .eq(AiCodingTaskArtifactEntity::getArtifactKey, artifactKey)
                        .last("LIMIT 1"));
    }

    JsonNode latestAppliedApplicationResult(String taskId) {
        AiCodingTaskArtifactEntity artifact = artifactMapper.selectOne(
                Wrappers.<AiCodingTaskArtifactEntity>lambdaQuery()
                        .eq(AiCodingTaskArtifactEntity::getTaskId, taskId)
                        .eq(
                                AiCodingTaskArtifactEntity::getProcessingStatus,
                                ArtifactProcessingStatus.APPLIED.name())
                        .orderByDesc(AiCodingTaskArtifactEntity::getArtifactId)
                        .last("LIMIT 1"));
        return artifact == null
                ? null
                : json.readJsonOrNull(artifact.getApplicationResultJson());
    }

    private void requireContract(
            AiCodingTaskEntity task,
            ArtifactEnvelope envelope) {
        String key = AiCodingTaskValues.requiredText(
                envelope.contract().key(),
                "artifact.contract.key",
                AiCodingTaskValues.CONTRACT_KEY_MAX_CHARACTERS);
        String version = AiCodingTaskValues.requiredText(
                envelope.contract().version(),
                "artifact.contract.version",
                AiCodingTaskValues.CONTRACT_VERSION_MAX_CHARACTERS);
        if (!task.getResultContractKey().equals(key)
                || !task.getResultContractVersion().equals(version)) {
            throw new IllegalArgumentException(
                    "artifact contract must be "
                            + task.getResultContractKey()
                            + "/"
                            + task.getResultContractVersion());
        }
    }

    private ArtifactReporter normalizeArtifactReporter(
            AiCodingTaskEntity task,
            ArtifactReporter reporter) {
        if (reporter == null) {
            return new ArtifactReporter(task.getExecutorProvider(), null);
        }
        String provider = AiCodingTaskValues.requiredEnum(
                ExecutorProvider.class,
                reporter.provider(),
                "artifact.reportedBy.provider").name();
        if (!task.getExecutorProvider().equals(provider)) {
            throw new IllegalArgumentException(
                    "artifact reporter provider must match task executorProvider");
        }
        return new ArtifactReporter(
                provider,
                sanitizer.sanitizeText(AiCodingTaskValues.optionalText(
                        reporter.sessionRef(),
                        "artifact.reportedBy.sessionRef",
                        AiCodingTaskValues.CLIENT_SESSION_REF_MAX_CHARACTERS)));
    }

    record ArtifactOutcome(AiCodingTaskEntity task, AiCodingTaskArtifactEntity artifact, JsonNode applicationResult) { }
    record AcceptanceOutcome(AiCodingTaskEntity task, AcceptanceGate gate) { }
    record AcceptanceGate(List<String> requiredKeys, List<ReadinessItem> readiness, boolean ready, List<String> blockers) { }
}
