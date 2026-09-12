package com.enterprise.ai.control.aicoding.application;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.LatestAppliedTaskArtifact;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ArtifactContractView;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ArtifactView;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ProjectView;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.QuestionView;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskContextView;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskContract;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskDescriptor;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskDetailView;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskEndpoints;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskEventView;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskRequiredResource;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskTargetView;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskView;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskValues;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskValues.ArtifactProcessingStatus;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskValues.ExecutionMode;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskValues.ExecutionStatus;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskValues.QuestionStatus;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskArtifactEntity;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskArtifactMapper;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskEntity;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskEventEntity;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskEventMapper;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ConnectionView;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskMapper;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskQuestionEntity;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskQuestionMapper;
import com.enterprise.ai.control.aicoding.provider.AiCodingTaskKindProvider;
import com.enterprise.ai.control.aicoding.provider.AiCodingTaskProviderRegistry;
import com.enterprise.ai.control.aicoding.provider.AiCodingContractResourceLoader;
import com.enterprise.ai.control.aicoding.security.AiCodingSensitiveJsonSanitizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
class AiCodingTaskQueryService {

    private final AiCodingTaskMapper taskMapper;
    private final AiCodingTaskEventMapper eventMapper;
    private final AiCodingTaskQuestionMapper questionMapper;
    private final AiCodingTaskArtifactMapper artifactMapper;
    private final AiCodingTaskProviderRegistry providerRegistry;
    private final AiCodingHandoffApplicationService handoffService;
    private final AiCodingSensitiveJsonSanitizer sanitizer;
    private final ObjectMapper objectMapper;
    private final AiCodingContractResourceLoader contractResourceLoader;
    private final AiCodingTaskJsonSupport json;
    private final AiCodingTaskStateChanges stateChanges;
    private final AiCodingTaskDescriptorReader readModels;
    private final AiCodingTaskDeliveryService delivery;

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
                readModels.targetsByTask(taskIds);
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
                                AiCodingTaskValues.optionalText(projectCode))
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
        AiCodingTaskEntity task = stateChanges.requireTask(taskId);
        AiCodingTaskKindProvider provider = providerRegistry.require(task.getTaskKind());
        return new TaskDetailView(
                toTaskView(task),
                delivery.readiness(
                        provider,
                        readModels.descriptor(task),
                        delivery.latestAppliedApplicationResult(task.getTaskId())),
                events(taskId, null),
                questions(taskId, null),
                artifacts(taskId));
    }

    public TaskContextView context(String taskId, String publicBaseUrl) {
        AiCodingTaskEntity task = stateChanges.requireTask(taskId);
        AiCodingTaskKindProvider provider = providerRegistry.require(task.getTaskKind());
        TaskDescriptor descriptor = readModels.descriptor(task);
        JsonNode domainContext = sanitizer.sanitize(provider.materializeContext(
                descriptor,
                descriptor.contextSnapshot(),
                publicBaseUrl));
        TaskContract contract = provider.contract();
        String taskRoot = trimTrailingSlash(publicBaseUrl)
                + "/api/ai-coding/tasks/" + task.getTaskId();
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
                delivery.readiness(
                        provider,
                        descriptor,
                        delivery.latestAppliedApplicationResult(task.getTaskId())),
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
                AiCodingTaskProtocolGuideFactory.create(
                        objectMapper, sanitizer, task.getExecutorProvider(), contract),
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

    public List<TaskEventView> events(String taskId, Long afterEventId) {
        AiCodingTaskEntity task = stateChanges.requireTask(taskId);
        return eventMapper.selectList(
                        Wrappers.<AiCodingTaskEventEntity>lambdaQuery()
                                .eq(AiCodingTaskEventEntity::getTaskId, task.getTaskId())
                                .gt(afterEventId != null,
                                        AiCodingTaskEventEntity::getId,
                                        afterEventId)
                                .orderByAsc(AiCodingTaskEventEntity::getId))
                .stream()
                .map(this::toEventView)
                .toList();
    }

    public List<QuestionView> questions(String taskId, LocalDateTime updatedAfter) {
        AiCodingTaskEntity task = stateChanges.requireTask(taskId);
        return questionMapper.selectList(
                        Wrappers.<AiCodingTaskQuestionEntity>lambdaQuery()
                                .eq(AiCodingTaskQuestionEntity::getTaskId, task.getTaskId())
                                .gt(updatedAfter != null,
                                        AiCodingTaskQuestionEntity::getUpdatedAt,
                                        updatedAfter)
                                .orderByAsc(AiCodingTaskQuestionEntity::getAskedAt))
                .stream()
                .map(this::toQuestionView)
                .toList();
    }

    public List<ArtifactView> artifacts(String taskId) {
        AiCodingTaskEntity task = stateChanges.requireTask(taskId);
        return artifactMapper.selectList(
                        Wrappers.<AiCodingTaskArtifactEntity>lambdaQuery()
                                .eq(AiCodingTaskArtifactEntity::getTaskId, task.getTaskId())
                                .orderByAsc(AiCodingTaskArtifactEntity::getArtifactId))
                .stream()
                .map(this::toArtifactView)
                .toList();
    }

    public TaskView task(String taskId) {
        return toTaskView(stateChanges.requireTask(taskId));
    }

    public TaskDescriptor descriptor(String taskId) {
        return readModels.descriptor(stateChanges.requireTask(taskId));
    }

    TaskView toTaskView(AiCodingTaskEntity task) {
        return toTaskView(
                task,
                handoffService.connection(task),
                readModels.targets(task.getTaskId()),
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
                task.getExecutionMode() == null
                        ? ExecutionMode.EXTERNAL_CLIENT.name()
                        : task.getExecutionMode(),
                task.getManagedExecutionId(),
                task.getSandboxProfile(),
                task.getManagedExecutionStatus(),
                task.getManagedPendingInteractionId(),
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
                effectiveConnection(task, connection),
                taskTargets,
                taskOpenQuestions);
    }

    private ConnectionView effectiveConnection(
            AiCodingTaskEntity task,
            ConnectionView externalConnection) {
        if (ExecutionMode.MANAGED_SANDBOX.name().equals(task.getExecutionMode())) {
            return new ConnectionView(
                    "NOT_APPLICABLE",
                    null,
                    task.getExecutorProvider(),
                    task.getManagedExecutionId(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);
        }
        return externalConnection;
    }

    private TaskEventView toEventView(AiCodingTaskEventEntity event) {
        return new TaskEventView(
                event.getId(),
                event.getClientEventId(),
                event.getEventType(),
                event.getExecutionStatusAfter(),
                event.getMessage(),
                json.readNode(event.getPayloadJson()),
                event.getActorType(),
                event.getActorName(),
                event.getCreatedAt());
    }

    QuestionView toQuestionView(AiCodingTaskQuestionEntity question) {
        return new QuestionView(
                question.getQuestionId(),
                question.getTitle(),
                question.getBody(),
                json.readStringList(question.getOptionsJson()),
                question.getStatus(),
                question.getAnswer(),
                question.getAskedBy(),
                question.getAnsweredBy(),
                question.getAskedAt(),
                question.getAnsweredAt(),
                question.getUpdatedAt());
    }

    ArtifactView toArtifactView(AiCodingTaskArtifactEntity artifact) {
        return new ArtifactView(
                artifact.getArtifactId(),
                artifact.getArtifactKey(),
                artifact.getContractKey(),
                artifact.getContractVersion(),
                artifact.getContentHash(),
                artifact.getProcessingStatus(),
                artifact.getValidationMessage(),
                json.readJsonOrNull(artifact.getApplicationResultJson()),
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
                : AiCodingTaskValues.firstText(text(project.get("name")), task.getProjectCode());
        String environment = project == null
                ? null
                : text(project.get("environment"));
        return new ProjectView(
                task.getProjectId(),
                task.getProjectCode(),
                name,
                environment);
    }

    private static String text(JsonNode value) {
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String trimTrailingSlash(String value) {
        String normalized = AiCodingTaskValues.requiredText(value, "publicBaseUrl");
        return normalized.endsWith("/")
                ? normalized.substring(0, normalized.length() - 1)
                : normalized;
    }
}
