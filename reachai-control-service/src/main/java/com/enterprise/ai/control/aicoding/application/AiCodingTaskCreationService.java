package com.enterprise.ai.control.aicoding.application;

import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.CreateTaskCommand;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskContract;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskDescriptor;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskTargetCommand;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskTargetView;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskValues;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskValues.AccessMode;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskValues.ActorType;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskValues.ExecutionMode;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskValues.ExecutionStatus;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskValues.ExecutorProvider;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskValues.ManagedSandboxProfile;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskValues.TargetRole;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskEntity;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskMapper;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskTargetEntity;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskTargetMapper;
import com.enterprise.ai.control.aicoding.provider.AiCodingTaskKindProvider;
import com.enterprise.ai.control.aicoding.provider.AiCodingTaskProviderRegistry;
import com.enterprise.ai.control.aicoding.security.AiCodingSensitiveJsonSanitizer;
import com.enterprise.ai.control.client.capability.CapabilityProjectOnboardingClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Owns task admission and its initial writes inside the caller's creation transaction. */
@Service
@RequiredArgsConstructor
public class AiCodingTaskCreationService {
    private final AiCodingTaskMapper taskMapper;
    private final AiCodingTaskTargetMapper targetMapper;
    private final AiCodingTaskProviderRegistry providerRegistry;
    private final CapabilityProjectOnboardingClient capabilityClient;
    private final AiCodingSensitiveJsonSanitizer sanitizer;
    private final ObjectMapper objectMapper;
    private final AiCodingTaskJsonSupport json;
    private final AiCodingTaskStateChanges stateChanges;

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public AiCodingTaskEntity create(CreateTaskCommand command) {
        if (command == null || command.projectId() == null) {
            throw new IllegalArgumentException("projectId is required");
        }
        String projectCode = AiCodingTaskValues.requiredText(
                command.projectCode(),
                "projectCode",
                AiCodingTaskValues.PROJECT_CODE_MAX_CHARACTERS);
        String taskKind = AiCodingTaskValues.requiredKey(command.taskKind(), "taskKind");
        String executorProvider = AiCodingTaskValues.requiredEnum(
                ExecutorProvider.class,
                command.executorProvider(),
                "executorProvider").name();
        ExecutionMode executionMode = StringUtils.hasText(command.executionMode())
                ? AiCodingTaskValues.requiredEnum(
                        ExecutionMode.class,
                        command.executionMode(),
                        "executionMode")
                : ExecutionMode.EXTERNAL_CLIENT;
        String title = AiCodingTaskValues.requiredText(
                command.title(),
                "title",
                AiCodingTaskValues.TASK_TITLE_MAX_CHARACTERS);
        String objective = AiCodingTaskValues.requiredTextUtf8(
                command.objective(),
                "objective",
                AiCodingTaskValues.TEXT_MAX_UTF8_BYTES);
        String createdBy = AiCodingTaskValues.optionalText(
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
        String sandboxProfile = normalizeSandboxProfile(
                executionMode,
                executorProvider,
                accessMode,
                command.sandboxProfile());
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
        task.setExecutionMode(executionMode.name());
        task.setSandboxProfile(sandboxProfile);
        task.setTitle(title);
        task.setObjective(objective);
        task.setAccessMode(accessMode.name());
        task.setExecutionStatus(ExecutionStatus.READY.name());
        task.setResultContractKey(AiCodingTaskValues.requiredText(
                contract.resultContractKey(),
                "contract.resultContractKey",
                AiCodingTaskValues.CONTRACT_KEY_MAX_CHARACTERS));
        task.setResultContractVersion(AiCodingTaskValues.requiredText(
                contract.resultContractVersion(),
                "contract.resultContractVersion",
                AiCodingTaskValues.CONTRACT_VERSION_MAX_CHARACTERS));
        task.setContextSnapshotJson(json.writeJson(contextSnapshot));
        task.setLastMessage(executionMode == ExecutionMode.MANAGED_SANDBOX
                ? "任务已创建，等待启动隔离执行"
                : "任务已创建，等待签发交接包");
        task.setLockVersion(0L);
        task.setCreatedBy(createdBy);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        taskMapper.insert(task);
        insertTargets(taskId, targets, now);
        stateChanges.appendEvent(
                task,
                null,
                "CREATED",
                task.getLastMessage(),
                null,
                ActorType.USER,
                createdBy);
        return task;
    }

    private String normalizeSandboxProfile(
            ExecutionMode executionMode,
            String executorProvider,
            AccessMode accessMode,
            String requestedProfile) {
        if (executionMode == ExecutionMode.EXTERNAL_CLIENT) {
            if (StringUtils.hasText(requestedProfile)) {
                throw new IllegalArgumentException(
                        "sandboxProfile is only valid for MANAGED_SANDBOX tasks");
            }
            return null;
        }
        if (!ExecutorProvider.CODEX.name().equals(executorProvider)) {
            throw new IllegalArgumentException(
                    "MANAGED_SANDBOX currently requires executorProvider CODEX");
        }
        ManagedSandboxProfile expected = accessMode == AccessMode.READ_ONLY
                ? ManagedSandboxProfile.ANALYZE_READONLY
                : ManagedSandboxProfile.WORKSPACE_PATCH;
        ManagedSandboxProfile profile = StringUtils.hasText(requestedProfile)
                ? AiCodingTaskValues.requiredEnum(
                        ManagedSandboxProfile.class,
                        requestedProfile,
                        "sandboxProfile")
                : expected;
        if (profile != expected) {
            throw new IllegalArgumentException(
                    "sandboxProfile does not match the task access mode");
        }
        return profile.name();
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
            String key = AiCodingTaskValues.requiredText(
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
            entity.setSnapshotJson(json.writeJson(target.snapshot()));
            entity.setCreatedAt(now);
            targetMapper.insert(entity);
        }
    }

    private static String newId(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
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

}
