package com.enterprise.ai.control.aicoding.domain;

import com.enterprise.ai.control.aicoding.domain.AiCodingTaskValues.ArtifactNextAction;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public final class AiCodingTaskModels {

    private AiCodingTaskModels() {
    }

    public record TaskTargetCommand(
            String targetType,
            String targetKey,
            String targetRole,
            String accessMode,
            JsonNode snapshot) {
    }

    public record CreateTaskCommand(
            Long projectId,
            String projectCode,
            String taskKind,
            String executorProvider,
            String title,
            String objective,
            String createdBy,
            List<TaskTargetCommand> targets,
            String executionMode,
            String sandboxProfile) {

        /** Keeps existing in-process producers source compatible. */
        public CreateTaskCommand(
                Long projectId,
                String projectCode,
                String taskKind,
                String executorProvider,
                String title,
                String objective,
                String createdBy,
                List<TaskTargetCommand> targets) {
            this(projectId, projectCode, taskKind, executorProvider, title,
                    objective, createdBy, targets, null, null);
        }
    }

    public record TaskTargetView(
            Long id,
            String targetType,
            String targetKey,
            String targetRole,
            String accessMode,
            JsonNode snapshot) {
    }

    public record ProjectView(
            Long id,
            String projectCode,
            String name,
            String environment) {
    }

    public record ConnectionView(
            String status,
            String timeoutReason,
            String clientProvider,
            String clientSessionRef,
            LocalDateTime activationExpiresAt,
            LocalDateTime activatedAt,
            LocalDateTime lastSeenAt,
            LocalDateTime leaseExpiresAt,
            LocalDateTime tokenExpiresAt,
            LocalDateTime closedAt) {
    }

    public record QuestionView(
            String questionId,
            String title,
            String body,
            List<String> options,
            String status,
            String answer,
            String askedBy,
            String answeredBy,
            LocalDateTime askedAt,
            LocalDateTime answeredAt,
            LocalDateTime updatedAt) {
    }

    public record TaskView(
            String taskId,
            Long projectId,
            String projectCode,
            String capabilityKey,
            String taskKind,
            String protocolVersion,
            String executorProvider,
            String executionMode,
            String managedExecutionId,
            String sandboxProfile,
            String managedExecutionStatus,
            String managedPendingInteractionId,
            String title,
            String objective,
            String accessMode,
            String executionStatus,
            String resultContractKey,
            String resultContractVersion,
            String lastMessage,
            String createdBy,
            LocalDateTime startedAt,
            LocalDateTime resultSubmittedAt,
            LocalDateTime completedAt,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            ConnectionView connection,
            List<TaskTargetView> targets,
            List<QuestionView> openQuestions) {

        /** Source-compatible constructor for existing external-client callers. */
        public TaskView(
                String taskId,
                Long projectId,
                String projectCode,
                String capabilityKey,
                String taskKind,
                String protocolVersion,
                String executorProvider,
                String title,
                String objective,
                String accessMode,
                String executionStatus,
                String resultContractKey,
                String resultContractVersion,
                String lastMessage,
                String createdBy,
                LocalDateTime startedAt,
                LocalDateTime resultSubmittedAt,
                LocalDateTime completedAt,
                LocalDateTime createdAt,
                LocalDateTime updatedAt,
                ConnectionView connection,
                List<TaskTargetView> targets,
                List<QuestionView> openQuestions) {
            this(taskId, projectId, projectCode, capabilityKey, taskKind,
                    protocolVersion, executorProvider, "EXTERNAL_CLIENT", null,
                    null, null, null, title, objective, accessMode,
                    executionStatus, resultContractKey, resultContractVersion,
                    lastMessage, createdBy, startedAt, resultSubmittedAt,
                    completedAt, createdAt, updatedAt, connection, targets,
                    openQuestions);
        }
    }

    public record TaskEventView(
            Long id,
            String clientEventId,
            String eventType,
            String executionStatusAfter,
            String message,
            JsonNode payload,
            String actorType,
            String actorName,
            LocalDateTime createdAt) {
    }

    public record ArtifactView(
            Long artifactId,
            String artifactKey,
            String contractKey,
            String contractVersion,
            String contentHash,
            String processingStatus,
            String validationMessage,
            JsonNode applicationResult,
            String reportedBy,
            LocalDateTime createdAt,
            LocalDateTime validatedAt,
            LocalDateTime appliedAt) {
    }

    public record TaskDetailView(
            TaskView task,
            List<ReadinessItem> readiness,
            List<TaskEventView> events,
            List<QuestionView> questions,
            List<ArtifactView> artifacts) {
    }

    public record AcceptanceVerificationView(
            TaskView task,
            List<ReadinessItem> readiness,
            boolean acceptanceReady,
            List<String> blockers) {
    }

    public record HandoffIssueCommand(String issuedBy) {
    }

    public record HandoffPackageView(
            String schema,
            String taskId,
            String handoffId,
            String protocolVersion,
            String executorProvider,
            String activationUrl,
            String activationCode,
            LocalDateTime activationExpiresAt,
            String prompt,
            int promptCharacters,
            Integer promptCharacterLimit) {
    }

    public record ActivationClient(String provider, String sessionRef) {
    }

    public record ActivationCommand(
            String schema,
            String activationCode,
            ActivationClient client) {
    }

    public record ActivationView(
            String schema,
            String taskId,
            String protocolVersion,
            String taskToken,
            String taskRoot,
            LocalDateTime tokenExpiresAt,
            LocalDateTime leaseExpiresAt,
            ClientSetupView clientSetup) {
    }

    public record ClientSetupView(
            String schema,
            String shell,
            String encoding,
            String scriptVersion,
            String sha256,
            String payload) {
    }

    public record CredentialPolicyView(
            Long projectId,
            int handoffActivationTtlHours,
            int taskTokenTtlHours,
            boolean customized,
            String updatedBy,
            LocalDateTime updatedAt) {
    }

    public record CredentialPolicyUpdateCommand(
            Integer handoffActivationTtlHours,
            Integer taskTokenTtlHours) {
    }

    public record ReadinessItem(
            String key,
            String label,
            String status,
            String message,
            JsonNode evidence) {
    }

    public record TaskContract(
            String capabilityKey,
            String taskKind,
            String accessMode,
            String primaryTargetType,
            String resultContractKey,
            String resultContractVersion,
            JsonNode jsonSchema,
            JsonNode example) {
    }

    public record TaskDescriptor(
            String taskId,
            Long projectId,
            String projectCode,
            String capabilityKey,
            String taskKind,
            String protocolVersion,
            String executorProvider,
            String title,
            String objective,
            String accessMode,
            String executionStatus,
            JsonNode contextSnapshot,
            List<TaskTargetView> targets,
            LocalDateTime startedAt,
            LocalDateTime createdAt) {
    }

    public record TaskEndpoints(
            String contextUrl,
            String heartbeatUrl,
            String eventsUrl,
            String questionsUrl,
            String artifactsUrl,
            String verificationsUrlTemplate) {
    }

    public record VerificationView(
            String schema,
            String verificationKey,
            String status,
            String message,
            JsonNode result) {
    }

    public record ArtifactContractView(
            String key,
            String version,
            JsonNode jsonSchema,
            JsonNode example,
            Map<String, JsonNode> referencedSchemas) {
    }

    /** A resource an AI Coding client must obtain before it changes code. */
    public record TaskRequiredResource(
            String id,
            String title,
            String type,
            String url,
            String entrypoint,
            String purpose,
            boolean requiredBeforeEditing) {
    }

    /** Distinguishes triggerable verification, observation and readiness gates. */
    public record VerificationGuideItem(
            String key,
            String type,
            String title,
            String description,
            String method,
            String url,
            List<String> prerequisites,
            List<String> affectsReadiness,
            String expectedResult,
            String evidenceSource) {
    }

    public record TaskCompletionPolicy(
            boolean eventsCanCompleteTask,
            String requiredSubmission,
            String submittedStatus,
            String acceptanceReadyStatus,
            String completedStatus,
            String authoritativeStatusField) {
    }

    public record ArtifactIdempotencyPolicy(
            String identityScope,
            boolean sameKeyRequiresSameContent,
            boolean correctedRevisionRequiresNewKey,
            String correctedRevisionKeyExample) {
    }

    public record TaskProtocolInputLimits(
            int clientEventIdMaxCharacters,
            int eventMessageMaxCharacters,
            int questionIdMaxCharacters,
            int questionTitleMaxCharacters,
            int questionOptionMaxCharacters,
            int questionOptionMaxCount,
            int textMaxUtf8Bytes,
            int artifactKeyMaxCharacters,
            int clientSessionRefMaxCharacters) {
    }

    /** Machine-readable execution-state contract for one client event type. */
    public record TaskEventStateRule(
            String eventType,
            List<String> acceptedExecutionStatuses,
            String statusEffect,
            String precondition) {
    }

    public record TaskProtocolGuide(
            String requestContentType,
            List<String> supportedEventTypes,
            List<TaskEventStateRule> eventStateRules,
            JsonNode startedEventExample,
            JsonNode progressEventExample,
            JsonNode questionExample,
            JsonNode artifactEnvelopeExample,
            TaskProtocolInputLimits inputLimits,
            ArtifactIdempotencyPolicy artifactIdempotencyPolicy,
            TaskCompletionPolicy completionPolicy) {
    }

    public record TaskContextView(
            String schema,
            String protocolVersion,
            TaskView task,
            ConnectionView connection,
            ProjectView project,
            List<TaskTargetView> targets,
            JsonNode scope,
            List<ReadinessItem> readiness,
            List<QuestionView> questions,
            TaskEndpoints endpoints,
            ArtifactContractView artifactContract,
            TaskProtocolGuide protocolGuide,
            List<TaskRequiredResource> requiredResources,
            List<VerificationGuideItem> verificationGuide,
            JsonNode domainContext) {
    }

    public record EventCommand(
            String schema,
            String clientEventId,
            String eventType,
            String message,
            JsonNode payload,
            String reportedBy) {
    }

    public record AskQuestionCommand(
            String schema,
            String clientEventId,
            String questionId,
            String title,
            String body,
            List<String> options,
            String askedBy) {
    }

    public record AnswerQuestionCommand(String answer, String answeredBy) {
    }

    public record ArtifactContractRef(String key, String version) {
    }

    public record ArtifactReporter(String provider, String sessionRef) {
    }

    public record ArtifactEnvelope(
            String schema,
            String clientEventId,
            String artifactKey,
            ArtifactContractRef contract,
            JsonNode content,
            ArtifactReporter reportedBy) {
    }

    public record ArtifactApplyResult(
            boolean applied,
            String message,
            ArtifactNextAction nextAction,
            JsonNode domainResult) {

        public static ArtifactApplyResult complete(String message, JsonNode domainResult) {
            return new ArtifactApplyResult(true, message, ArtifactNextAction.COMPLETE, domainResult);
        }

        public static ArtifactApplyResult acceptanceRequired(
                String message,
                JsonNode domainResult) {
            return new ArtifactApplyResult(
                    true,
                    message,
                    ArtifactNextAction.ACCEPTANCE_REQUIRED,
                    domainResult);
        }

        public static ArtifactApplyResult failed(
                String message,
                JsonNode domainResult) {
            return new ArtifactApplyResult(
                    true,
                    message,
                    ArtifactNextAction.FAIL,
                    domainResult);
        }

        public static ArtifactApplyResult rejected(String message) {
            return rejected(message, null);
        }

        public static ArtifactApplyResult rejected(
                String message,
                JsonNode domainResult) {
            return new ArtifactApplyResult(
                    false,
                    message,
                    ArtifactNextAction.STAY_APPLIED,
                    domainResult);
        }
    }

    public record ArtifactApplyView(
            TaskView task,
            ArtifactView artifact,
            JsonNode domainResult) {
    }
}
