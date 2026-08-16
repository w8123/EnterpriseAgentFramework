package com.enterprise.ai.control.aicoding.application;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.AnswerQuestionCommand;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.AcceptanceVerificationView;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ArtifactApplyResult;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ArtifactApplyView;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ArtifactContractView;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ArtifactEnvelope;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ArtifactIdempotencyPolicy;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ArtifactReporter;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ArtifactView;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.AskQuestionCommand;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.CreateTaskCommand;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.EventCommand;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ProjectView;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.QuestionView;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ReadinessItem;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskContextView;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskCompletionPolicy;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskContract;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskDescriptor;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskDetailView;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskEndpoints;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskEventStateRule;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskEventView;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskProtocolGuide;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskProtocolInputLimits;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskRequiredResource;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskTargetCommand;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskTargetView;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskView;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.VerificationView;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskStateMachine;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskValues;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskValues.AccessMode;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskValues.ActorType;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskValues.ArtifactNextAction;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskValues.ArtifactProcessingStatus;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskValues.ExecutionStatus;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskValues.ExecutorProvider;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskValues.QuestionStatus;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskValues.TargetRole;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskArtifactEntity;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskArtifactMapper;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskEntity;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskEventEntity;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskEventMapper;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ConnectionView;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskMapper;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskQuestionEntity;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskQuestionMapper;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskTargetEntity;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskTargetMapper;
import com.enterprise.ai.control.aicoding.provider.AiCodingTaskKindProvider;
import com.enterprise.ai.control.aicoding.provider.AiCodingTaskProviderRegistry;
import com.enterprise.ai.control.aicoding.provider.AiCodingContractResourceLoader;
import com.enterprise.ai.control.aicoding.security.AiCodingSensitiveJsonSanitizer;
import com.enterprise.ai.control.client.capability.CapabilityProjectOnboardingClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AiCodingTaskApplicationService {

    private final AiCodingTaskMapper taskMapper;
    private final AiCodingTaskTargetMapper targetMapper;
    private final AiCodingTaskEventMapper eventMapper;
    private final AiCodingTaskQuestionMapper questionMapper;
    private final AiCodingTaskArtifactMapper artifactMapper;
    private final AiCodingTaskProviderRegistry providerRegistry;
    private final AiCodingArtifactProviderExecutor providerExecutor;
    private final AiCodingHandoffApplicationService handoffService;
    private final CapabilityProjectOnboardingClient capabilityClient;
    private final AiCodingSensitiveJsonSanitizer sanitizer;
    private final ObjectMapper objectMapper;
    private final AiCodingContractResourceLoader contractResourceLoader;

    @Transactional
    public TaskView create(CreateTaskCommand command) {
        if (command == null || command.projectId() == null) {
            throw new IllegalArgumentException("projectId is required");
        }
        String projectCode = requireText(
                command.projectCode(),
                "projectCode",
                AiCodingTaskValues.PROJECT_CODE_MAX_CHARACTERS);
        String taskKind = AiCodingTaskValues.requiredKey(command.taskKind(), "taskKind");
        String executorProvider = AiCodingTaskValues.requiredEnum(
                ExecutorProvider.class,
                command.executorProvider(),
                "executorProvider").name();
        String title = requireText(
                command.title(),
                "title",
                AiCodingTaskValues.TASK_TITLE_MAX_CHARACTERS);
        String objective = AiCodingTaskValues.requiredTextUtf8(
                command.objective(),
                "objective",
                AiCodingTaskValues.TEXT_MAX_UTF8_BYTES);
        String createdBy = textOrNull(
                command.createdBy(),
                "createdBy",
                AiCodingTaskValues.ACTOR_NAME_MAX_CHARACTERS);

        AiCodingTaskKindProvider provider = providerRegistry.require(taskKind);
        TaskContract contract = provider.contract();
        String capabilityKey = AiCodingTaskValues.requiredKey(
                contract.capabilityKey(),
                "contract.capabilityKey");
        AccessMode accessMode = AiCodingTaskValues.requiredEnum(
                AccessMode.class,
                contract.accessMode(),
                "contract.accessMode");
        List<TaskTargetView> targets = normalizeTargets(command.targets(), contract, accessMode);
        requireProjectTargetMatches(projectCode, targets, contract);
        verifyProject(command.projectId(), projectCode);

        String taskId = newId("ait_");
        TaskDescriptor draft = new TaskDescriptor(
                taskId,
                command.projectId(),
                projectCode,
                capabilityKey,
                taskKind,
                AiCodingTaskValues.PROTOCOL_VERSION,
                executorProvider,
                title,
                objective,
                accessMode.name(),
                ExecutionStatus.READY.name(),
                objectMapper.createObjectNode(),
                targets,
                null,
                null);
        JsonNode contextSnapshot = sanitizer.sanitize(provider.buildContext(draft));
        LocalDateTime now = LocalDateTime.now();

        AiCodingTaskEntity task = new AiCodingTaskEntity();
        task.setTaskId(taskId);
        task.setProjectId(command.projectId());
        task.setProjectCode(projectCode);
        task.setCapabilityKey(capabilityKey);
        task.setTaskKind(taskKind);
        task.setProtocolVersion(AiCodingTaskValues.PROTOCOL_VERSION);
        task.setExecutorProvider(executorProvider);
        task.setTitle(title);
        task.setObjective(objective);
        task.setAccessMode(accessMode.name());
        task.setExecutionStatus(ExecutionStatus.READY.name());
        task.setResultContractKey(requireText(
                contract.resultContractKey(),
                "contract.resultContractKey",
                AiCodingTaskValues.CONTRACT_KEY_MAX_CHARACTERS));
        task.setResultContractVersion(requireText(
                contract.resultContractVersion(),
                "contract.resultContractVersion",
                AiCodingTaskValues.CONTRACT_VERSION_MAX_CHARACTERS));
        task.setContextSnapshotJson(writeJson(contextSnapshot));
        task.setLastMessage("任务已创建，等待签发交接包");
        task.setLockVersion(0L);
        task.setCreatedBy(createdBy);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        taskMapper.insert(task);
        insertTargets(taskId, targets, now);
        appendEvent(
                task,
                null,
                "CREATED",
                task.getLastMessage(),
                null,
                ActorType.USER,
                createdBy);
        return toTaskView(task);
    }

    public List<TaskView> list(
            Long projectId,
            String projectCode,
            String taskKind,
            String executionStatus,
            int limit) {
        List<AiCodingTaskEntity> tasks = selectTasks(
                projectId,
                projectCode,
                taskKind,
                executionStatus,
                limit);
        if (tasks.isEmpty()) {
            return List.of();
        }
        List<String> taskIds = tasks.stream()
                .map(AiCodingTaskEntity::getTaskId)
                .toList();
        Map<String, ConnectionView> connections =
                handoffService.connections(tasks);
        Map<String, List<TaskTargetView>> targetsByTask =
                targetsByTask(taskIds);
        Map<String, List<QuestionView>> openQuestionsByTask =
                openQuestionsByTask(taskIds);
        return tasks.stream()
                .map(task -> toTaskView(
                        task,
                        connections.get(task.getTaskId()),
                        targetsByTask.getOrDefault(task.getTaskId(), List.of()),
                        openQuestionsByTask.getOrDefault(
                                task.getTaskId(),
                                List.of())))
                .toList();
    }

    /**
     * Reads the latest task header and the first applied artifact in task
     * recency order without hydrating handoffs, targets, or questions.
     *
     * <p>This projection is used by lightweight status surfaces such as the
     * page-map header. Keeping the artifact lookup batched avoids one query per
     * historical task while preserving the existing fallback to an older
     * applied scan when the newest task is still running.</p>
     */
    public LatestAppliedTaskArtifact latestAppliedTaskArtifact(
            String projectCode,
            String taskKind,
            int limit) {
        List<AiCodingTaskEntity> tasks = selectTasks(
                null,
                projectCode,
                taskKind,
                null,
                limit);
        return latestAppliedTaskArtifact(tasks.stream()
                .map(task -> new TaskArtifactCandidate(
                        task.getTaskId(),
                        task.getExecutionStatus()))
                .toList());
    }

    public LatestAppliedTaskArtifact latestAppliedTaskArtifact(
            List<TaskView> tasks,
            String taskKind,
            int limit) {
        List<TaskArtifactCandidate> candidates = tasks == null
                ? List.of()
                : tasks.stream()
                        .filter(task -> Objects.equals(taskKind, task.taskKind()))
                        .limit(Math.min(Math.max(limit, 1), 200))
                        .map(task -> new TaskArtifactCandidate(
                                task.taskId(),
                                task.executionStatus()))
                        .toList();
        return latestAppliedTaskArtifact(candidates);
    }

    private LatestAppliedTaskArtifact latestAppliedTaskArtifact(
            List<TaskArtifactCandidate> tasks) {
        if (tasks.isEmpty()) {
            return new LatestAppliedTaskArtifact(null, null, null);
        }

        List<String> taskIds = tasks.stream()
                .map(TaskArtifactCandidate::taskId)
                .toList();
        Map<String, ArtifactView> firstAppliedArtifactByTask =
                new LinkedHashMap<>();
        artifactMapper.selectList(
                        Wrappers.<AiCodingTaskArtifactEntity>lambdaQuery()
                                .in(AiCodingTaskArtifactEntity::getTaskId, taskIds)
                                .eq(AiCodingTaskArtifactEntity::getProcessingStatus,
                                        ArtifactProcessingStatus.APPLIED.name())
                                .orderByAsc(AiCodingTaskArtifactEntity::getArtifactId))
                .forEach(artifact -> firstAppliedArtifactByTask.putIfAbsent(
                        artifact.getTaskId(),
                        toArtifactView(artifact)));

        ArtifactView applied = tasks.stream()
                .map(task -> firstAppliedArtifactByTask.get(task.taskId()))
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
        TaskArtifactCandidate latest = tasks.get(0);
        return new LatestAppliedTaskArtifact(
                latest.taskId(),
                latest.executionStatus(),
                applied == null ? null : applied.applicationResult());
    }

    public record LatestAppliedTaskArtifact(
            String latestTaskId,
            String latestExecutionStatus,
            JsonNode applicationResult) {
    }

    private record TaskArtifactCandidate(
            String taskId,
            String executionStatus) {
    }

    private List<AiCodingTaskEntity> selectTasks(
            Long projectId,
            String projectCode,
            String taskKind,
            String executionStatus,
            int limit) {
        String normalizedTaskKind = StringUtils.hasText(taskKind)
                ? AiCodingTaskValues.requiredKey(taskKind, "taskKind")
                : null;
        ExecutionStatus normalizedStatus = StringUtils.hasText(executionStatus)
                ? AiCodingTaskValues.requiredEnum(
                        ExecutionStatus.class,
                        executionStatus,
                        "executionStatus")
                : null;
        return taskMapper.selectList(
                Wrappers.<AiCodingTaskEntity>lambdaQuery()
                        .eq(projectId != null, AiCodingTaskEntity::getProjectId, projectId)
                        .eq(StringUtils.hasText(projectCode),
                                AiCodingTaskEntity::getProjectCode,
                                textOrNull(projectCode))
                        .eq(normalizedTaskKind != null,
                                AiCodingTaskEntity::getTaskKind,
                                normalizedTaskKind)
                        .eq(normalizedStatus != null,
                                AiCodingTaskEntity::getExecutionStatus,
                                normalizedStatus == null ? null : normalizedStatus.name())
                        .orderByDesc(AiCodingTaskEntity::getUpdatedAt)
                        .last("LIMIT " + Math.min(Math.max(limit, 1), 200)));
    }

    public TaskDetailView detail(String taskId) {
        AiCodingTaskEntity task = requireTask(taskId);
        AiCodingTaskKindProvider provider = providerRegistry.require(task.getTaskKind());
        return new TaskDetailView(
                toTaskView(task),
                readiness(
                        provider,
                        toDescriptor(task),
                        latestAppliedApplicationResult(task.getTaskId())),
                events(taskId, null),
                questions(taskId, null),
                artifacts(taskId));
    }

    public TaskContextView context(String taskId, String publicBaseUrl) {
        AiCodingTaskEntity task = requireTask(taskId);
        AiCodingTaskKindProvider provider = providerRegistry.require(task.getTaskKind());
        TaskDescriptor descriptor = toDescriptor(task);
        JsonNode domainContext = sanitizer.sanitize(provider.materializeContext(
                descriptor,
                descriptor.contextSnapshot(),
                publicBaseUrl));
        TaskContract contract = provider.contract();
        String taskRoot = trimTrailingSlash(publicBaseUrl)
                + "/api/ai-coding/tasks/" + taskId;
        JsonNode scope = scope(task, descriptor.targets(), domainContext);
        ProjectView project = project(task, domainContext);
        return new TaskContextView(
                AiCodingTaskValues.TASK_CONTEXT_SCHEMA,
                task.getProtocolVersion(),
                toTaskView(task),
                handoffService.connection(task),
                project,
                descriptor.targets(),
                scope,
                readiness(
                        provider,
                        descriptor,
                        latestAppliedApplicationResult(task.getTaskId())),
                questions(taskId, null),
                new TaskEndpoints(
                        taskRoot + "/context",
                        taskRoot + "/heartbeat",
                        taskRoot + "/events",
                        taskRoot + "/questions",
                        taskRoot + "/artifacts",
                        taskRoot + "/verifications/{verificationKey}"),
                new ArtifactContractView(
                        contract.resultContractKey(),
                        contract.resultContractVersion(),
                        sanitizer.sanitize(contract.jsonSchema()),
                        sanitizer.sanitize(contract.example()),
                        sanitizeReferencedSchemas(contractResourceLoader.referencedSchemas(
                                contract.jsonSchema()))),
                protocolGuide(task, contract),
                sanitizeRequiredResources(provider.requiredResources(
                        descriptor,
                        publicBaseUrl)),
                sanitizeVerificationGuide(provider.verificationGuide(
                        descriptor,
                        taskRoot)),
                domainContext);
    }

    private Map<String, JsonNode> sanitizeReferencedSchemas(
            Map<String, JsonNode> referencedSchemas) {
        if (referencedSchemas == null || referencedSchemas.isEmpty()) {
            return Map.of();
        }
        Map<String, JsonNode> sanitized = new LinkedHashMap<>();
        referencedSchemas.forEach((name, schema) -> sanitized.put(
                name,
                sanitizer.sanitize(schema)));
        return Map.copyOf(sanitized);
    }

    private List<TaskRequiredResource> sanitizeRequiredResources(
            List<TaskRequiredResource> resources) {
        return resources == null ? List.of() : List.copyOf(resources);
    }

    private List<com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.VerificationGuideItem>
            sanitizeVerificationGuide(
            List<com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.VerificationGuideItem> guide) {
        return guide == null ? List.of() : List.copyOf(guide);
    }

    @Transactional
    public VerificationView requestVerification(
            String taskId,
            String verificationKey) {
        AiCodingTaskEntity task = requireTask(taskId);
        requireStatus(task, ExecutionStatus.RUNNING);
        String normalizedKey = AiCodingTaskValues.requiredKey(
                verificationKey,
                "verificationKey");
        AiCodingTaskKindProvider provider =
                providerRegistry.require(task.getTaskKind());
        JsonNode result = sanitizer.sanitize(provider.requestVerification(
                toDescriptor(task),
                normalizedKey));
        String message = "AI Coding verification completed: " + normalizedKey;
        task.setLastMessage(message);
        task.setUpdatedAt(LocalDateTime.now());
        requireUpdated(task);
        appendEvent(
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
    public TaskView recordEvent(String taskId, EventCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("event request is required");
        }
        requireSchema(command.schema(), AiCodingTaskValues.EVENT_SCHEMA, "event.schema");
        String clientEventId = requireText(
                command.clientEventId(),
                "clientEventId",
                AiCodingTaskValues.CLIENT_EVENT_ID_MAX_CHARACTERS);
        AiCodingTaskEntity task = requireTask(taskId);
        String eventType = requireText(command.eventType(), "eventType")
                .toUpperCase(Locale.ROOT);
        ActorType actorType = ActorType.AI_CODING;
        String actorName = normalizeClientProvider(
                task,
                command.reportedBy(),
                "event.reportedBy");
        String eventMessage = requireText(
                sanitizer.sanitizeText(switch (eventType) {
                    case "STARTED" -> firstText(
                            command.message(),
                            "AI Coding 客户端已开始");
                    case "PROGRESS", "FAILED" -> requireText(
                            command.message(),
                            "message");
                    case "RESUMED" -> firstText(
                            command.message(),
                            "AI Coding 客户端已读取回答并继续");
                    default -> throw new IllegalArgumentException(
                            "unsupported eventType: " + eventType);
                }),
                "message",
                AiCodingTaskValues.EVENT_MESSAGE_MAX_CHARACTERS);
        JsonNode safePayload = sanitizer.sanitize(command.payload());
        AiCodingTaskEventEntity duplicateEvent =
                findClientEvent(taskId, clientEventId);
        if (duplicateEvent != null) {
            requireSameEvent(
                    duplicateEvent,
                    eventType,
                    eventMessage,
                    safePayload,
                    actorName);
            return toTaskView(task);
        }
        ExecutionStatus current = status(task);

        switch (eventType) {
            case "STARTED" -> {
                if (current == ExecutionStatus.RUNNING) {
                    appendEvent(
                            task,
                            clientEventId,
                            eventType,
                            eventMessage,
                            safePayload,
                            actorType,
                            actorName);
                } else if (current == ExecutionStatus.READY) {
                    transition(
                            task,
                            ExecutionStatus.RUNNING,
                            clientEventId,
                            eventType,
                            eventMessage,
                            safePayload,
                            actorType,
                            actorName);
                } else {
                    throw new IllegalStateException(
                            "STARTED is only valid for READY or RUNNING tasks; "
                                    + "current status is " + current);
                }
            }
            case "PROGRESS" -> {
                if (current != ExecutionStatus.RUNNING
                        && current != ExecutionStatus.WAITING_USER) {
                    throw new IllegalStateException(
                            "PROGRESS is only valid for RUNNING or WAITING_USER tasks; "
                                    + "current status is " + current);
                }
                task.setLastMessage(eventMessage);
                task.setUpdatedAt(LocalDateTime.now());
                requireUpdated(task);
                appendEvent(
                        task,
                        clientEventId,
                        eventType,
                        eventMessage,
                        safePayload,
                        actorType,
                        actorName);
            }
            case "RESUMED" -> {
                requireStatus(task, ExecutionStatus.WAITING_USER);
                if (openQuestionCount(taskId) > 0) {
                    throw new IllegalStateException(
                            "task still has unanswered questions");
                }
                transition(
                        task,
                        ExecutionStatus.RUNNING,
                        clientEventId,
                        eventType,
                        eventMessage,
                        safePayload,
                        actorType,
                        actorName);
            }
            case "FAILED" -> transition(
                    task,
                    ExecutionStatus.FAILED,
                    clientEventId,
                    eventType,
                    eventMessage,
                    safePayload,
                    actorType,
                    actorName);
            default -> throw new IllegalStateException(
                    "validated eventType was not handled: " + eventType);
        }
        if (status(task).terminal()) {
            handoffService.closeTaskHandoffs(taskId, "TASK_" + task.getExecutionStatus());
        }
        return toTaskView(task);
    }

    @Transactional
    public QuestionView askQuestion(String taskId, AskQuestionCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("question request is required");
        }
        requireSchema(
                command.schema(),
                AiCodingTaskValues.QUESTION_SCHEMA,
                "question.schema");
        String clientEventId = requireText(
                command.clientEventId(),
                "clientEventId",
                AiCodingTaskValues.CLIENT_EVENT_ID_MAX_CHARACTERS);
        String questionId = requireText(
                command.questionId(),
                "questionId",
                AiCodingTaskValues.QUESTION_ID_MAX_CHARACTERS);
        AiCodingTaskEntity task = requireTask(taskId);
        String title = requireText(
                sanitizer.sanitizeText(requireText(command.title(), "title")),
                "title",
                AiCodingTaskValues.QUESTION_TITLE_MAX_CHARACTERS);
        String body = AiCodingTaskValues.requiredTextUtf8(
                sanitizer.sanitizeText(requireText(command.body(), "body")),
                "body",
                AiCodingTaskValues.TEXT_MAX_UTF8_BYTES);
        List<String> options = normalizeOptions(command.options()).stream()
                .map(sanitizer::sanitizeText)
                .toList();
        String optionsJson = AiCodingTaskValues.requireMaxUtf8Bytes(
                writeJson(options),
                "options",
                AiCodingTaskValues.TEXT_MAX_UTF8_BYTES);
        String askedBy = normalizeClientProvider(
                task,
                command.askedBy(),
                "question.askedBy");
        AiCodingTaskQuestionEntity existing = questionMapper.selectById(questionId);
        if (existing != null) {
            if (!existing.getTaskId().equals(taskId)) {
                throw new IllegalStateException(
                        "questionId is already used by another task");
            }
            requireSameQuestion(
                    existing,
                    title,
                    body,
                    optionsJson,
                    askedBy);
            AiCodingTaskEventEntity duplicateEvent =
                    findClientEvent(taskId, clientEventId);
            if (duplicateEvent != null) {
                JsonNode payload = readJsonOrNull(
                        duplicateEvent.getPayloadJson());
                if (!"QUESTION".equals(duplicateEvent.getEventType())
                        || payload == null
                        || !questionId.equals(
                                text(payload.get("questionId")))) {
                    throw new IllegalStateException(
                            "clientEventId is already used by a different request");
                }
            }
            return toQuestionView(existing);
        }
        if (findClientEvent(taskId, clientEventId) != null) {
            throw new IllegalStateException(
                    "clientEventId is already used without this questionId");
        }

        requireStatus(task, ExecutionStatus.RUNNING);
        LocalDateTime now = LocalDateTime.now();
        AiCodingTaskQuestionEntity question = new AiCodingTaskQuestionEntity();
        question.setQuestionId(questionId);
        question.setTaskId(taskId);
        question.setTitle(title);
        question.setBody(body);
        question.setOptionsJson(optionsJson);
        question.setStatus(QuestionStatus.OPEN.name());
        question.setAskedBy(askedBy);
        question.setAskedAt(now);
        question.setUpdatedAt(now);
        questionMapper.insert(question);

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("questionId", questionId);
        transition(
                task,
                ExecutionStatus.WAITING_USER,
                clientEventId,
                "QUESTION",
                "AI Coding 客户端等待用户回答：" + question.getTitle(),
                payload,
                ActorType.AI_CODING,
                question.getAskedBy());
        return toQuestionView(question);
    }

    @Transactional
    public QuestionView answerQuestion(
            String taskId,
            String questionId,
            AnswerQuestionCommand command) {
        AiCodingTaskEntity task = requireTask(taskId);
        AiCodingTaskQuestionEntity question = questionMapper.selectById(
                requireText(questionId, "questionId"));
        if (question == null || !taskId.equals(question.getTaskId())) {
            throw new IllegalArgumentException(
                    "AI Coding question not found: " + questionId);
        }
        String answer = AiCodingTaskValues.requiredTextUtf8(
                sanitizer.sanitizeText(requireText(
                        command == null ? null : command.answer(),
                        "answer")),
                "answer",
                AiCodingTaskValues.TEXT_MAX_UTF8_BYTES);
        String answeredBy = textOrNull(
                command.answeredBy(),
                "answeredBy",
                AiCodingTaskValues.ACTOR_NAME_MAX_CHARACTERS);
        if (QuestionStatus.ANSWERED.name().equals(question.getStatus())) {
            if (!Objects.equals(question.getAnswer(), answer)) {
                throw new IllegalStateException(
                        "question was already answered with different content");
            }
            return toQuestionView(question);
        }
        if (!QuestionStatus.OPEN.name().equals(question.getStatus())) {
            throw new IllegalStateException("question is not open");
        }
        requireStatus(task, ExecutionStatus.WAITING_USER);
        LocalDateTime now = LocalDateTime.now();
        int answered = questionMapper.update(
                null,
                Wrappers.<AiCodingTaskQuestionEntity>update()
                        .eq("question_id", questionId)
                        .eq("task_id", taskId)
                        .eq("status", QuestionStatus.OPEN.name())
                        .set("status", QuestionStatus.ANSWERED.name())
                        .set("answer", answer)
                        .set("answered_by", answeredBy)
                        .set("answered_at", now)
                        .set("updated_at", now));
        if (answered != 1) {
            AiCodingTaskQuestionEntity raced =
                    questionMapper.selectById(questionId);
            if (raced != null
                    && taskId.equals(raced.getTaskId())
                    && QuestionStatus.ANSWERED.name().equals(raced.getStatus())) {
                if (!Objects.equals(raced.getAnswer(), answer)) {
                    throw new IllegalStateException(
                            "question was already answered with different content");
                }
                return toQuestionView(raced);
            }
            throw new IllegalStateException("question is not open");
        }
        question.setStatus(QuestionStatus.ANSWERED.name());
        question.setAnswer(answer);
        question.setAnsweredBy(answeredBy);
        question.setAnsweredAt(now);
        question.setUpdatedAt(now);

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("questionId", questionId);
        appendEvent(
                task,
                null,
                "QUESTION_ANSWERED",
                "用户已回答，等待 " + task.getExecutorProvider() + " 读取并继续",
                payload,
                ActorType.USER,
                answeredBy);
        task.setLastMessage(
                "用户已回答问题，等待 " + task.getExecutorProvider() + " 继续");
        task.setUpdatedAt(now);
        requireUpdated(task);
        return toQuestionView(question);
    }

    @Transactional
    public ArtifactApplyView submitArtifact(String taskId, ArtifactEnvelope envelope) {
        if (envelope == null) {
            throw new IllegalArgumentException("artifact request is required");
        }
        requireSchema(
                envelope.schema(),
                AiCodingTaskValues.ARTIFACT_SCHEMA,
                "artifact.schema");
        String clientEventId = requireText(
                envelope.clientEventId(),
                "clientEventId",
                AiCodingTaskValues.CLIENT_EVENT_ID_MAX_CHARACTERS);
        String artifactKey = requireText(
                envelope.artifactKey(),
                "artifactKey",
                AiCodingTaskValues.ARTIFACT_KEY_MAX_CHARACTERS);
        if (envelope.contract() == null) {
            throw new IllegalArgumentException("artifact.contract is required");
        }
        if (envelope.content() == null || envelope.content().isNull()) {
            throw new IllegalArgumentException("artifact.content is required");
        }

        AiCodingTaskEntity task = requireTask(taskId);
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
        String contentJson = canonicalJson(sanitizedContent);
        String contentHash = sha256(contentJson);
        if (duplicate != null) {
            if (!contentHash.equals(duplicate.getContentHash())) {
                throw new IllegalStateException(
                        "artifactKey was already submitted with different content");
            }
            return new ArtifactApplyView(
                    toTaskView(task),
                    toArtifactView(duplicate),
                    readJsonOrNull(duplicate.getApplicationResultJson()));
        }
        requireStatus(task, ExecutionStatus.RUNNING);
        requireText(clientEventId, "clientEventId");
        if (findClientEvent(taskId, clientEventId) != null) {
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
                return new ArtifactApplyView(
                        toTaskView(requireTask(taskId)),
                        toArtifactView(raced),
                        readJsonOrNull(raced.getApplicationResultJson()));
            }
            throw new IllegalStateException(
                    "artifactKey was concurrently submitted with different content",
                    ex);
        }

        transition(
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
                toDescriptor(task),
                safeEnvelope);
        artifact.setValidatedAt(LocalDateTime.now());
        artifact.setApplicationResultJson(
                result.domainResult() == null ? null : writeJson(result.domainResult()));
        if (!result.applied()) {
            artifact.setProcessingStatus(ArtifactProcessingStatus.REJECTED.name());
            artifact.setValidationMessage(AiCodingTaskValues.fitText(
                    firstText(result.message(), "Artifact validation failed"),
                    AiCodingTaskValues.EVENT_MESSAGE_MAX_CHARACTERS));
            artifactMapper.updateById(artifact);
            transition(
                    task,
                    ExecutionStatus.RUNNING,
                    null,
                    "ARTIFACT_REJECTED",
                    artifact.getValidationMessage(),
                    null,
                    ActorType.REACHAI,
                    "task-kernel");
            return new ArtifactApplyView(
                    toTaskView(task),
                    toArtifactView(artifact),
                    result.domainResult());
        }

        artifact.setProcessingStatus(ArtifactProcessingStatus.APPLIED.name());
        artifact.setValidationMessage(AiCodingTaskValues.fitText(
                textOrNull(result.message()),
                AiCodingTaskValues.EVENT_MESSAGE_MAX_CHARACTERS));
        artifact.setAppliedAt(LocalDateTime.now());
        artifactMapper.updateById(artifact);
        transition(
                task,
                ExecutionStatus.RESULT_APPLIED,
                null,
                "RESULT_APPLIED",
                firstText(result.message(), "Artifact 已校验并应用"),
                null,
                ActorType.REACHAI,
                "task-kernel");

        ArtifactNextAction nextAction = result.nextAction() == null
                ? ArtifactNextAction.STAY_APPLIED
                : result.nextAction();
        if (nextAction == ArtifactNextAction.COMPLETE) {
            transition(
                    task,
                    ExecutionStatus.COMPLETED,
                    null,
                    "COMPLETED",
                    firstText(result.message(), "AI Coding 任务已完成"),
                    null,
                    ActorType.REACHAI,
                    "task-kernel");
            handoffService.closeTaskHandoffs(taskId, "TASK_COMPLETED");
        } else if (nextAction == ArtifactNextAction.FAIL) {
            transition(
                    task,
                    ExecutionStatus.FAILED,
                    null,
                    "FAILED",
                    firstText(result.message(), "AI Coding 任务结果未通过"),
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
        return new ArtifactApplyView(
                toTaskView(task),
                toArtifactView(artifact),
                result.domainResult());
    }

    @Transactional
    public AcceptanceVerificationView verifyAcceptanceReadiness(String taskId) {
        AiCodingTaskEntity task = requireTask(taskId);
        AiCodingTaskKindProvider provider = providerRegistry.require(task.getTaskKind());
        if (provider.acceptanceReadinessKeys() == null
                || provider.acceptanceReadinessKeys().isEmpty()) {
            throw new IllegalStateException(
                    "task kind does not define platform acceptance-readiness gates");
        }
        if (status(task) == ExecutionStatus.ACCEPTANCE_READY) {
            AcceptanceGate gate = evaluateAcceptanceGate(
                    provider,
                    toDescriptor(task),
                    latestAppliedApplicationResult(taskId));
            return new AcceptanceVerificationView(
                    toTaskView(task),
                    gate.readiness(),
                    gate.ready(),
                    gate.blockers());
        }
        requireStatus(task, ExecutionStatus.RESULT_APPLIED);
        AcceptanceGate gate = advanceToAcceptanceReadyIfVerified(
                task,
                provider,
                latestAppliedApplicationResult(taskId));
        return new AcceptanceVerificationView(
                toTaskView(task),
                gate.readiness(),
                gate.ready(),
                gate.blockers());
    }

    @Transactional
    public TaskView finishAcceptance(
            String taskId,
            boolean passed,
            String message,
            String actor) {
        AiCodingTaskEntity task = requireTask(taskId);
        requireStatus(task, ExecutionStatus.ACCEPTANCE_READY);
        AiCodingTaskKindProvider provider = providerRegistry.require(task.getTaskKind());
        AcceptanceGate gate = evaluateAcceptanceGate(
                provider,
                toDescriptor(task),
                latestAppliedApplicationResult(taskId));
        if (!gate.ready()) {
            throw new IllegalStateException(
                    "platform acceptance-readiness gates are not PASS: "
                            + String.join("; ", gate.blockers()));
        }
        transition(
                task,
                passed ? ExecutionStatus.COMPLETED : ExecutionStatus.FAILED,
                null,
                passed ? "ACCEPTANCE_PASSED" : "ACCEPTANCE_FAILED",
                requireText(
                        message,
                        "message",
                        AiCodingTaskValues.EVENT_MESSAGE_MAX_CHARACTERS),
                null,
                ActorType.REACHAI,
                textOrNull(
                        actor,
                        "actor",
                        AiCodingTaskValues.ACTOR_NAME_MAX_CHARACTERS));
        handoffService.closeTaskHandoffs(
                taskId,
                passed ? "TASK_COMPLETED" : "TASK_FAILED");
        return toTaskView(task);
    }

    @Transactional
    public TaskView cancel(String taskId, String actor) {
        AiCodingTaskEntity task = requireTask(taskId);
        if (status(task) == ExecutionStatus.CANCELLED) {
            return toTaskView(task);
        }
        String normalizedActor = textOrNull(
                actor,
                "actor",
                AiCodingTaskValues.ACTOR_NAME_MAX_CHARACTERS);
        transition(
                task,
                ExecutionStatus.CANCELLED,
                null,
                "CANCELLED",
                "任务已取消",
                null,
                ActorType.USER,
                normalizedActor);
        handoffService.closeTaskHandoffs(taskId, "TASK_CANCELLED");
        return toTaskView(task);
    }

    public List<TaskEventView> events(String taskId, Long afterEventId) {
        requireTask(taskId);
        return eventMapper.selectList(
                        Wrappers.<AiCodingTaskEventEntity>lambdaQuery()
                                .eq(AiCodingTaskEventEntity::getTaskId, taskId)
                                .gt(afterEventId != null,
                                        AiCodingTaskEventEntity::getId,
                                        afterEventId)
                                .orderByAsc(AiCodingTaskEventEntity::getId))
                .stream()
                .map(this::toEventView)
                .toList();
    }

    public List<QuestionView> questions(String taskId, LocalDateTime updatedAfter) {
        requireTask(taskId);
        return questionMapper.selectList(
                        Wrappers.<AiCodingTaskQuestionEntity>lambdaQuery()
                                .eq(AiCodingTaskQuestionEntity::getTaskId, taskId)
                                .gt(updatedAfter != null,
                                        AiCodingTaskQuestionEntity::getUpdatedAt,
                                        updatedAfter)
                                .orderByAsc(AiCodingTaskQuestionEntity::getAskedAt))
                .stream()
                .map(this::toQuestionView)
                .toList();
    }

    public List<ArtifactView> artifacts(String taskId) {
        requireTask(taskId);
        return artifactMapper.selectList(
                        Wrappers.<AiCodingTaskArtifactEntity>lambdaQuery()
                                .eq(AiCodingTaskArtifactEntity::getTaskId, taskId)
                                .orderByAsc(AiCodingTaskArtifactEntity::getArtifactId))
                .stream()
                .map(this::toArtifactView)
                .toList();
    }

    public TaskView task(String taskId) {
        return toTaskView(requireTask(taskId));
    }

    public TaskDescriptor descriptor(String taskId) {
        return toDescriptor(requireTask(taskId));
    }

    private List<TaskTargetView> normalizeTargets(
            List<TaskTargetCommand> commands,
            TaskContract contract,
            AccessMode defaultAccessMode) {
        if (commands == null || commands.isEmpty()) {
            throw new IllegalArgumentException("at least one task target is required");
        }
        List<TaskTargetView> targets = new ArrayList<>();
        Set<String> identities = new HashSet<>();
        int primaryCount = 0;
        for (TaskTargetCommand command : commands) {
            if (command == null) {
                throw new IllegalArgumentException("task target is required");
            }
            String type = AiCodingTaskValues.requireMaxCharacters(
                    AiCodingTaskValues.requiredKey(
                            command.targetType(),
                            "target.targetType"),
                    "target.targetType",
                    AiCodingTaskValues.TARGET_TYPE_MAX_CHARACTERS);
            String key = requireText(
                    command.targetKey(),
                    "target.targetKey",
                    AiCodingTaskValues.TARGET_KEY_MAX_CHARACTERS);
            TargetRole role = AiCodingTaskValues.requiredEnum(
                    TargetRole.class,
                    command.targetRole(),
                    "target.targetRole");
            AccessMode accessMode = StringUtils.hasText(command.accessMode())
                    ? AiCodingTaskValues.requiredEnum(
                            AccessMode.class,
                            command.accessMode(),
                            "target.accessMode")
                    : defaultAccessMode;
            if (defaultAccessMode == AccessMode.READ_ONLY
                    && accessMode == AccessMode.READ_WRITE) {
                throw new IllegalArgumentException(
                        "READ_ONLY tasks cannot contain READ_WRITE targets");
            }
            if (role == TargetRole.PRIMARY) {
                primaryCount++;
                String requiredPrimaryType = AiCodingTaskValues.requiredKey(
                        contract.primaryTargetType(),
                        "contract.primaryTargetType");
                if (!requiredPrimaryType.equals(type)) {
                    throw new IllegalArgumentException(
                            "primary target type must be " + requiredPrimaryType);
                }
                if (accessMode != defaultAccessMode) {
                    throw new IllegalArgumentException(
                            "primary target accessMode must be " + defaultAccessMode.name());
                }
            }
            String identity = type + "\n" + key + "\n" + role.name();
            if (!identities.add(identity)) {
                throw new IllegalArgumentException("duplicate task target: " + type + "/" + key);
            }
            targets.add(new TaskTargetView(
                    null,
                    type,
                    key,
                    role.name(),
                    accessMode.name(),
                    sanitizer.sanitize(command.snapshot())));
        }
        if (primaryCount != 1) {
            throw new IllegalArgumentException(
                    "task must contain exactly one PRIMARY target");
        }
        return List.copyOf(targets);
    }

    private void verifyProject(Long projectId, String projectCode) {
        Map<String, Object> project = capabilityClient.getProjectById(projectId);
        if (project == null || project.isEmpty()) {
            throw new IllegalArgumentException(
                    "project does not exist or returned no identity");
        }
        Long actualId = longValue(project.get("id"));
        if (actualId == null) {
            actualId = longValue(project.get("projectId"));
        }
        String actualCode = stringValue(project.get("projectCode"));
        if (actualId == null && !StringUtils.hasText(actualCode)) {
            throw new IllegalArgumentException(
                    "project lookup returned no projectId or projectCode");
        }
        if (actualId != null && !Objects.equals(actualId, projectId)) {
            throw new IllegalArgumentException(
                    "project lookup returned a different projectId");
        }
        if (StringUtils.hasText(actualCode)
                && !projectCode.equalsIgnoreCase(actualCode.trim())) {
            throw new IllegalArgumentException(
                    "projectId and projectCode do not refer to the same project");
        }
    }

    private void requireProjectTargetMatches(
            String projectCode,
            List<TaskTargetView> targets,
            TaskContract contract) {
        String primaryTargetType = AiCodingTaskValues.requiredKey(
                contract.primaryTargetType(),
                "contract.primaryTargetType");
        if (!"PROJECT".equals(primaryTargetType)) {
            return;
        }
        TaskTargetView primary = targets.stream()
                .filter(target -> TargetRole.PRIMARY.name().equals(
                        target.targetRole()))
                .findFirst()
                .orElseThrow();
        if (!projectCode.equalsIgnoreCase(primary.targetKey().trim())) {
            throw new IllegalArgumentException(
                    "primary PROJECT target must equal projectCode");
        }
    }

    private void insertTargets(
            String taskId,
            List<TaskTargetView> targets,
            LocalDateTime now) {
        for (TaskTargetView target : targets) {
            AiCodingTaskTargetEntity entity = new AiCodingTaskTargetEntity();
            entity.setTaskId(taskId);
            entity.setTargetType(target.targetType());
            entity.setTargetKey(target.targetKey());
            entity.setTargetRole(target.targetRole());
            entity.setAccessMode(target.accessMode());
            entity.setSnapshotJson(writeJson(target.snapshot()));
            entity.setCreatedAt(now);
            targetMapper.insert(entity);
        }
    }

    private List<TaskTargetView> targets(String taskId) {
        return targetMapper.selectList(
                        Wrappers.<AiCodingTaskTargetEntity>lambdaQuery()
                                .eq(AiCodingTaskTargetEntity::getTaskId, taskId)
                                .orderByAsc(AiCodingTaskTargetEntity::getId))
                .stream()
                .map(target -> new TaskTargetView(
                        target.getId(),
                        target.getTargetType(),
                        target.getTargetKey(),
                        target.getTargetRole(),
                        target.getAccessMode(),
                        readNode(target.getSnapshotJson())))
                .toList();
    }

    private Map<String, List<TaskTargetView>> targetsByTask(
            List<String> taskIds) {
        Map<String, List<TaskTargetView>> result = new LinkedHashMap<>();
        targetMapper.selectList(
                        Wrappers.<AiCodingTaskTargetEntity>lambdaQuery()
                                .in(AiCodingTaskTargetEntity::getTaskId, taskIds)
                                .orderByAsc(AiCodingTaskTargetEntity::getId))
                .forEach(target -> result
                        .computeIfAbsent(
                                target.getTaskId(),
                                ignored -> new ArrayList<>())
                        .add(new TaskTargetView(
                                target.getId(),
                                target.getTargetType(),
                                target.getTargetKey(),
                                target.getTargetRole(),
                                target.getAccessMode(),
                                readNode(target.getSnapshotJson()))));
        return result;
    }

    private AcceptanceGate advanceToAcceptanceReadyIfVerified(
            AiCodingTaskEntity task,
            AiCodingTaskKindProvider provider,
            JsonNode applicationResult) {
        AcceptanceGate gate = evaluateAcceptanceGate(
                provider,
                toDescriptor(task),
                applicationResult);
        ObjectNode payload = objectMapper.createObjectNode();
        payload.set(
                "requiredReadinessKeys",
                objectMapper.valueToTree(gate.requiredKeys()));
        payload.set("readiness", objectMapper.valueToTree(gate.readiness()));
        payload.set("blockers", objectMapper.valueToTree(gate.blockers()));
        if (gate.ready()) {
            transition(
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
        requireUpdated(task);
        appendEvent(
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

        Map<String, ReadinessItem> byKey = new LinkedHashMap<>();
        for (ReadinessItem item : readiness) {
            if (item != null && StringUtils.hasText(item.key())) {
                byKey.put(item.key().trim().toUpperCase(Locale.ROOT), item);
            }
        }
        List<String> blockers = new ArrayList<>();
        for (String key : requiredKeys) {
            ReadinessItem item = byKey.get(key);
            if (item == null) {
                blockers.add(key + " 未返回平台验证结果");
                continue;
            }
            String itemStatus = StringUtils.hasText(item.status())
                    ? item.status().trim().toUpperCase(Locale.ROOT)
                    : "UNKNOWN";
            if (!"PASS".equals(itemStatus)) {
                blockers.add(firstText(item.label(), key)
                        + "（" + itemStatus + "）");
            }
        }
        return new AcceptanceGate(
                requiredKeys,
                readiness,
                blockers.isEmpty(),
                List.copyOf(blockers));
    }

    private List<ReadinessItem> readiness(
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

    private TaskDescriptor toDescriptor(AiCodingTaskEntity task) {
        return new TaskDescriptor(
                task.getTaskId(),
                task.getProjectId(),
                task.getProjectCode(),
                task.getCapabilityKey(),
                task.getTaskKind(),
                task.getProtocolVersion(),
                task.getExecutorProvider(),
                task.getTitle(),
                task.getObjective(),
                task.getAccessMode(),
                task.getExecutionStatus(),
                readNode(task.getContextSnapshotJson()),
                targets(task.getTaskId()),
                task.getStartedAt(),
                task.getCreatedAt());
    }

    private record AcceptanceGate(
            List<String> requiredKeys,
            List<ReadinessItem> readiness,
            boolean ready,
            List<String> blockers) {
    }

    private TaskView toTaskView(AiCodingTaskEntity task) {
        return toTaskView(
                task,
                handoffService.connection(task),
                targets(task.getTaskId()),
                openQuestions(task.getTaskId()));
    }

    private TaskView toTaskView(
            AiCodingTaskEntity task,
            ConnectionView connection,
            List<TaskTargetView> taskTargets,
            List<QuestionView> taskOpenQuestions) {
        return new TaskView(
                task.getTaskId(),
                task.getProjectId(),
                task.getProjectCode(),
                task.getCapabilityKey(),
                task.getTaskKind(),
                task.getProtocolVersion(),
                task.getExecutorProvider(),
                task.getTitle(),
                task.getObjective(),
                task.getAccessMode(),
                task.getExecutionStatus(),
                task.getResultContractKey(),
                task.getResultContractVersion(),
                task.getLastMessage(),
                task.getCreatedBy(),
                task.getStartedAt(),
                task.getResultSubmittedAt(),
                task.getCompletedAt(),
                task.getCreatedAt(),
                task.getUpdatedAt(),
                connection,
                taskTargets,
                taskOpenQuestions);
    }

    private TaskEventView toEventView(AiCodingTaskEventEntity event) {
        return new TaskEventView(
                event.getId(),
                event.getClientEventId(),
                event.getEventType(),
                event.getExecutionStatusAfter(),
                event.getMessage(),
                readNode(event.getPayloadJson()),
                event.getActorType(),
                event.getActorName(),
                event.getCreatedAt());
    }

    private QuestionView toQuestionView(AiCodingTaskQuestionEntity question) {
        return new QuestionView(
                question.getQuestionId(),
                question.getTitle(),
                question.getBody(),
                readStringList(question.getOptionsJson()),
                question.getStatus(),
                question.getAnswer(),
                question.getAskedBy(),
                question.getAnsweredBy(),
                question.getAskedAt(),
                question.getAnsweredAt(),
                question.getUpdatedAt());
    }

    private ArtifactView toArtifactView(AiCodingTaskArtifactEntity artifact) {
        return new ArtifactView(
                artifact.getArtifactId(),
                artifact.getArtifactKey(),
                artifact.getContractKey(),
                artifact.getContractVersion(),
                artifact.getContentHash(),
                artifact.getProcessingStatus(),
                artifact.getValidationMessage(),
                readJsonOrNull(artifact.getApplicationResultJson()),
                artifact.getReportedBy(),
                artifact.getCreatedAt(),
                artifact.getValidatedAt(),
                artifact.getAppliedAt());
    }

    private List<QuestionView> openQuestions(String taskId) {
        return questionMapper.selectList(
                        Wrappers.<AiCodingTaskQuestionEntity>lambdaQuery()
                                .eq(AiCodingTaskQuestionEntity::getTaskId, taskId)
                                .eq(AiCodingTaskQuestionEntity::getStatus,
                                        QuestionStatus.OPEN.name())
                                .orderByAsc(AiCodingTaskQuestionEntity::getAskedAt))
                .stream()
                .map(this::toQuestionView)
                .toList();
    }

    private Map<String, List<QuestionView>> openQuestionsByTask(
            List<String> taskIds) {
        Map<String, List<QuestionView>> result = new LinkedHashMap<>();
        questionMapper.selectList(
                        Wrappers.<AiCodingTaskQuestionEntity>lambdaQuery()
                                .in(AiCodingTaskQuestionEntity::getTaskId, taskIds)
                                .eq(AiCodingTaskQuestionEntity::getStatus,
                                        QuestionStatus.OPEN.name())
                                .orderByAsc(AiCodingTaskQuestionEntity::getAskedAt))
                .forEach(question -> result
                        .computeIfAbsent(
                                question.getTaskId(),
                                ignored -> new ArrayList<>())
                        .add(toQuestionView(question)));
        return result;
    }

    private long openQuestionCount(String taskId) {
        return questionMapper.selectCount(
                Wrappers.<AiCodingTaskQuestionEntity>lambdaQuery()
                        .eq(AiCodingTaskQuestionEntity::getTaskId, taskId)
                        .eq(AiCodingTaskQuestionEntity::getStatus,
                                QuestionStatus.OPEN.name()));
    }

    private AiCodingTaskEventEntity findClientEvent(
            String taskId,
            String clientEventId) {
        return eventMapper.selectOne(
                Wrappers.<AiCodingTaskEventEntity>lambdaQuery()
                        .eq(AiCodingTaskEventEntity::getTaskId, taskId)
                        .eq(AiCodingTaskEventEntity::getClientEventId, clientEventId)
                        .last("LIMIT 1"));
    }

    private void requireSameEvent(
            AiCodingTaskEventEntity existing,
            String eventType,
            String message,
            JsonNode payload,
            String actorName) {
        JsonNode storedPayload = readJsonOrNull(existing.getPayloadJson());
        if (!eventType.equals(existing.getEventType())
                || !Objects.equals(message, existing.getMessage())
                || !sameJson(storedPayload, payload)
                || !Objects.equals(textOrNull(actorName), existing.getActorName())) {
            throw new IllegalStateException(
                    "clientEventId was already submitted with different content");
        }
    }

    private void requireSameQuestion(
            AiCodingTaskQuestionEntity existing,
            String title,
            String body,
            String optionsJson,
            String askedBy) {
        if (!title.equals(existing.getTitle())
                || !body.equals(existing.getBody())
                || !sameJson(
                        readJsonOrNull(existing.getOptionsJson()),
                        readJsonOrNull(optionsJson))
                || !Objects.equals(textOrNull(askedBy), existing.getAskedBy())) {
            throw new IllegalStateException(
                    "questionId was already submitted with different content");
        }
    }

    private boolean sameJson(JsonNode left, JsonNode right) {
        JsonNode normalizedLeft = left == null || left.isNull()
                ? null
                : canonicalize(left);
        JsonNode normalizedRight = right == null || right.isNull()
                ? null
                : canonicalize(right);
        return Objects.equals(normalizedLeft, normalizedRight);
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

    private JsonNode latestAppliedApplicationResult(String taskId) {
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
                : readJsonOrNull(artifact.getApplicationResultJson());
    }

    private void requireContract(
            AiCodingTaskEntity task,
            ArtifactEnvelope envelope) {
        String key = requireText(
                envelope.contract().key(),
                "artifact.contract.key",
                AiCodingTaskValues.CONTRACT_KEY_MAX_CHARACTERS);
        String version = requireText(
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
                sanitizer.sanitizeText(textOrNull(
                        reporter.sessionRef(),
                        "artifact.reportedBy.sessionRef",
                        AiCodingTaskValues.CLIENT_SESSION_REF_MAX_CHARACTERS)));
    }

    private String normalizeClientProvider(
            AiCodingTaskEntity task,
            String suppliedProvider,
            String field) {
        if (!StringUtils.hasText(suppliedProvider)) {
            return task.getExecutorProvider();
        }
        String provider = AiCodingTaskValues.requiredEnum(
                ExecutorProvider.class,
                suppliedProvider,
                field).name();
        if (!task.getExecutorProvider().equals(provider)) {
            throw new IllegalArgumentException(
                    field + " must match task executorProvider");
        }
        return provider;
    }

    private void requireStatus(
            AiCodingTaskEntity task,
            ExecutionStatus expected) {
        ExecutionStatus current = status(task);
        if (current != expected) {
            throw new IllegalStateException(
                    "task must be " + expected + " but is " + current);
        }
    }

    private void transition(
            AiCodingTaskEntity task,
            ExecutionStatus next,
            String clientEventId,
            String eventType,
            String message,
            JsonNode payload,
            ActorType actorType,
            String actorName) {
        ExecutionStatus current = status(task);
        AiCodingTaskStateMachine.requireTransition(current, next);
        String summary = AiCodingTaskValues.fitText(
                message,
                AiCodingTaskValues.EVENT_MESSAGE_MAX_CHARACTERS);
        LocalDateTime now = LocalDateTime.now();
        task.setExecutionStatus(next.name());
        task.setLastMessage(summary);
        task.setUpdatedAt(now);
        if (next == ExecutionStatus.RUNNING && task.getStartedAt() == null) {
            task.setStartedAt(now);
        }
        if (next == ExecutionStatus.RESULT_SUBMITTED) {
            task.setResultSubmittedAt(now);
        }
        if (next.terminal()) {
            task.setCompletedAt(now);
            closeOpenQuestions(task.getTaskId(), now);
        }
        requireUpdated(task);
        appendEvent(
                task,
                clientEventId,
                eventType,
                summary,
                payload,
                actorType,
                actorName);
    }

    private void appendEvent(
            AiCodingTaskEntity task,
            String clientEventId,
            String eventType,
            String message,
            JsonNode payload,
            ActorType actorType,
            String actorName) {
        AiCodingTaskEventEntity event = new AiCodingTaskEventEntity();
        event.setTaskId(task.getTaskId());
        event.setClientEventId(textOrNull(
                clientEventId,
                "clientEventId",
                AiCodingTaskValues.CLIENT_EVENT_ID_MAX_CHARACTERS));
        event.setEventType(requireText(
                eventType,
                "eventType",
                AiCodingTaskValues.EVENT_TYPE_MAX_CHARACTERS));
        event.setExecutionStatusAfter(task.getExecutionStatus());
        event.setMessage(AiCodingTaskValues.fitText(
                textOrNull(message),
                AiCodingTaskValues.EVENT_MESSAGE_MAX_CHARACTERS));
        event.setPayloadJson(payload == null ? null : writeJson(payload));
        event.setActorType(actorType.name());
        event.setActorName(textOrNull(
                actorName,
                "actorName",
                AiCodingTaskValues.ACTOR_NAME_MAX_CHARACTERS));
        event.setCreatedAt(LocalDateTime.now());
        eventMapper.insert(event);
    }

    private void closeOpenQuestions(String taskId, LocalDateTime now) {
        questionMapper.update(
                null,
                Wrappers.<AiCodingTaskQuestionEntity>lambdaUpdate()
                        .eq(AiCodingTaskQuestionEntity::getTaskId, taskId)
                        .eq(AiCodingTaskQuestionEntity::getStatus,
                                QuestionStatus.OPEN.name())
                        .set(AiCodingTaskQuestionEntity::getStatus,
                                QuestionStatus.CLOSED.name())
                        .set(AiCodingTaskQuestionEntity::getUpdatedAt, now));
    }

    private void requireUpdated(AiCodingTaskEntity task) {
        int updated = taskMapper.updateById(task);
        if (updated != 1) {
            throw new IllegalStateException(
                    "AI Coding task was concurrently modified; reload and retry");
        }
    }

    private AiCodingTaskEntity requireTask(String taskId) {
        AiCodingTaskEntity task = taskMapper.selectById(requireText(taskId, "taskId"));
        if (task == null) {
            throw new IllegalArgumentException(
                    "AI Coding task not found: " + taskId);
        }
        return task;
    }

    private JsonNode scope(
            AiCodingTaskEntity task,
            List<TaskTargetView> targets,
            JsonNode domainContext) {
        if (domainContext != null
                && domainContext.has("scope")
                && domainContext.get("scope").isObject()) {
            return domainContext.get("scope");
        }
        ObjectNode scope = objectMapper.createObjectNode();
        scope.put("accessMode", task.getAccessMode());
        scope.set("targets", objectMapper.valueToTree(targets));
        scope.putArray("excludedRules").add("Do not access unrelated modules.");
        return scope;
    }

    private ProjectView project(
            AiCodingTaskEntity task,
            JsonNode domainContext) {
        JsonNode project = domainContext == null
                ? null
                : domainContext.get("project");
        String name = project == null
                ? task.getProjectCode()
                : firstText(text(project.get("name")), task.getProjectCode());
        String environment = project == null
                ? null
                : text(project.get("environment"));
        return new ProjectView(
                task.getProjectId(),
                task.getProjectCode(),
                name,
                environment);
    }

    private TaskProtocolGuide protocolGuide(
            AiCodingTaskEntity task,
            TaskContract contract) {
        ObjectNode started = protocolEventExample(
                task,
                "STARTED",
                "已获取 ReachAI context，开始执行任务");
        ObjectNode progress = protocolEventExample(
                task,
                "PROGRESS",
                "已完成当前阶段，继续执行下一阶段");

        ObjectNode question = objectMapper.createObjectNode();
        question.put("schema", AiCodingTaskValues.QUESTION_SCHEMA);
        question.put("clientEventId", "replace-with-stable-guid");
        question.put("questionId", "replace-with-stable-question-id");
        question.put("title", "需要确认的问题");
        question.put("body", "请提供完成当前任务所需的业务信息");
        question.putArray("options");
        question.put("askedBy", task.getExecutorProvider());

        ObjectNode artifact = objectMapper.createObjectNode();
        artifact.put("schema", AiCodingTaskValues.ARTIFACT_SCHEMA);
        artifact.put("clientEventId", "replace-with-stable-guid");
        artifact.put("artifactKey", "replace-with-stable-artifact-key");
        artifact.putObject("contract")
                .put("key", contract.resultContractKey())
                .put("version", contract.resultContractVersion());
        artifact.set("content", sanitizer.sanitize(contract.example()));
        artifact.putObject("reportedBy")
                .put("provider", task.getExecutorProvider())
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

    private ObjectNode protocolEventExample(
            AiCodingTaskEntity task,
            String eventType,
            String message) {
        ObjectNode event = objectMapper.createObjectNode();
        event.put("schema", AiCodingTaskValues.EVENT_SCHEMA);
        event.put("clientEventId", "replace-with-stable-guid");
        event.put("eventType", eventType);
        event.put("message", message);
        event.putObject("payload").put("stepKey", "replace-with-current-step");
        event.put("reportedBy", task.getExecutorProvider());
        return event;
    }

    private String canonicalJson(JsonNode value) {
        return writeJson(canonicalize(value));
    }

    private JsonNode canonicalize(JsonNode value) {
        if (value == null || value.isNull() || value.isValueNode()) {
            return value;
        }
        if (value.isArray()) {
            ArrayNode array = objectMapper.createArrayNode();
            value.forEach(item -> array.add(canonicalize(item)));
            return array;
        }
        ObjectNode object = objectMapper.createObjectNode();
        List<String> names = new ArrayList<>();
        value.fieldNames().forEachRemaining(names::add);
        names.stream().sorted().forEach(name ->
                object.set(name, canonicalize(value.get(name))));
        return object;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(
                    value == null ? objectMapper.createObjectNode() : value);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException(
                    "AI Coding value is not JSON serializable",
                    ex);
        }
    }

    private JsonNode readNode(String json) {
        if (!StringUtils.hasText(json)) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException ex) {
            return objectMapper.createObjectNode();
        }
    }

    private JsonNode readJsonOrNull(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException ex) {
            return null;
        }
    }

    private List<String> readStringList(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (JsonProcessingException ex) {
            return List.of();
        }
    }

    private static ExecutionStatus status(AiCodingTaskEntity task) {
        return ExecutionStatus.valueOf(task.getExecutionStatus());
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private static String newId(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
    }

    private static String requireText(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private static String requireText(
            String value,
            String field,
            int maxCharacters) {
        return AiCodingTaskValues.requiredText(
                value,
                field,
                maxCharacters);
    }

    private static List<String> normalizeOptions(List<String> options) {
        if (options == null || options.isEmpty()) {
            return List.of();
        }
        if (options.size() > AiCodingTaskValues.QUESTION_OPTION_MAX_COUNT) {
            throw new IllegalArgumentException(
                    "options must not contain more than "
                            + AiCodingTaskValues.QUESTION_OPTION_MAX_COUNT
                            + " items");
        }
        List<String> normalized = new ArrayList<>(options.size());
        for (String option : options) {
            normalized.add(requireText(
                    option,
                    "options[]",
                    AiCodingTaskValues.QUESTION_OPTION_MAX_CHARACTERS));
        }
        return List.copyOf(normalized);
    }

    private static void requireSchema(
            String actual,
            String expected,
            String field) {
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException(field + " must be " + expected);
        }
    }

    private static String textOrNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private static String textOrNull(
            String value,
            String field,
            int maxCharacters) {
        return AiCodingTaskValues.optionalText(
                value,
                field,
                maxCharacters);
    }

    private static String firstText(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String text(JsonNode value) {
        return value == null || value.isNull() ? null : value.asText();
    }

    private static Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null || !StringUtils.hasText(String.valueOf(value))) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String trimTrailingSlash(String value) {
        String normalized = requireText(value, "publicBaseUrl");
        return normalized.endsWith("/")
                ? normalized.substring(0, normalized.length() - 1)
                : normalized;
    }
}
