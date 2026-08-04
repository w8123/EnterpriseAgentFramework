package com.enterprise.ai.control.aicoding.application;

import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.CredentialPolicyUpdateCommand;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.CredentialPolicyView;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskValues;
import com.enterprise.ai.control.aicoding.persistence.AiCodingProjectCredentialPolicyEntity;
import com.enterprise.ai.control.aicoding.persistence.AiCodingProjectCredentialPolicyMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class AiCodingCredentialPolicyService {

    static final int MIN_TTL_HOURS = 1;
    static final int MAX_HANDOFF_TTL_HOURS = 24 * 7;
    static final int MAX_TASK_TOKEN_TTL_HOURS = 24 * 30;

    private final AiCodingProjectCredentialPolicyMapper policyMapper;
    private final AiCodingTaskProperties properties;

    public CredentialPolicyView get(Long projectId) {
        Long requiredProjectId = requireProjectId(projectId);
        AiCodingProjectCredentialPolicyEntity entity =
                policyMapper.selectById(requiredProjectId);
        if (entity == null) {
            return new CredentialPolicyView(
                    requiredProjectId,
                    defaultHours(properties.getActivationTtl(),
                            MAX_HANDOFF_TTL_HOURS),
                    defaultHours(properties.getTokenTtl(),
                            MAX_TASK_TOKEN_TTL_HOURS),
                    false,
                    null,
                    null);
        }
        return toView(entity, true);
    }

    @Transactional
    public CredentialPolicyView update(
            Long projectId,
            CredentialPolicyUpdateCommand command,
            String updatedBy) {
        Long requiredProjectId = requireProjectId(projectId);
        if (command == null) {
            throw new IllegalArgumentException("credential policy is required");
        }
        CredentialPolicyView current = get(requiredProjectId);
        int handoffHours = validateHours(
                command.handoffActivationTtlHours() == null
                        ? current.handoffActivationTtlHours()
                        : command.handoffActivationTtlHours(),
                MAX_HANDOFF_TTL_HOURS,
                "handoffActivationTtlHours");
        int tokenHours = validateHours(
                command.taskTokenTtlHours() == null
                        ? current.taskTokenTtlHours()
                        : command.taskTokenTtlHours(),
                MAX_TASK_TOKEN_TTL_HOURS,
                "taskTokenTtlHours");
        LocalDateTime now = LocalDateTime.now();
        AiCodingProjectCredentialPolicyEntity entity =
                policyMapper.selectById(requiredProjectId);
        if (entity == null) {
            entity = new AiCodingProjectCredentialPolicyEntity();
            entity.setProjectId(requiredProjectId);
            entity.setCreatedAt(now);
            entity.setHandoffActivationTtlHours(handoffHours);
            entity.setTaskTokenTtlHours(tokenHours);
            entity.setUpdatedBy(normalizeActor(updatedBy));
            entity.setUpdatedAt(now);
            policyMapper.insert(entity);
        } else {
            entity.setHandoffActivationTtlHours(handoffHours);
            entity.setTaskTokenTtlHours(tokenHours);
            entity.setUpdatedBy(normalizeActor(updatedBy));
            entity.setUpdatedAt(now);
            policyMapper.updateById(entity);
        }
        return toView(entity, true);
    }

    public Duration handoffActivationTtl(Long projectId) {
        return Duration.ofHours(get(projectId).handoffActivationTtlHours());
    }

    public Duration taskTokenTtl(Long projectId) {
        return Duration.ofHours(get(projectId).taskTokenTtlHours());
    }

    private static CredentialPolicyView toView(
            AiCodingProjectCredentialPolicyEntity entity,
            boolean customized) {
        return new CredentialPolicyView(
                entity.getProjectId(),
                entity.getHandoffActivationTtlHours(),
                entity.getTaskTokenTtlHours(),
                customized,
                entity.getUpdatedBy(),
                entity.getUpdatedAt());
    }

    private static Long requireProjectId(Long projectId) {
        if (projectId == null || projectId <= 0) {
            throw new IllegalArgumentException("projectId must be positive");
        }
        return projectId;
    }

    private static int defaultHours(Duration duration, int maximum) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalStateException(
                    "AI Coding credential TTL must be positive");
        }
        long seconds = duration.getSeconds();
        long hours = Math.max(1, (seconds + 3599) / 3600);
        if (hours > maximum) {
            throw new IllegalStateException(
                    "AI Coding credential TTL exceeds supported maximum");
        }
        return Math.toIntExact(hours);
    }

    private static int validateHours(
            int value,
            int maximum,
            String field) {
        if (value < MIN_TTL_HOURS || value > maximum) {
            throw new IllegalArgumentException(
                    field + " must be between " + MIN_TTL_HOURS
                            + " and " + maximum);
        }
        return value;
    }

    private static String normalizeActor(String value) {
        return AiCodingTaskValues.optionalText(
                value,
                "updatedBy",
                AiCodingTaskValues.ACTOR_NAME_MAX_CHARACTERS);
    }
}
