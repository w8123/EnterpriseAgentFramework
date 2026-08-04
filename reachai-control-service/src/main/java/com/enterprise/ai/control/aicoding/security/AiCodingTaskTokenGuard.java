package com.enterprise.ai.control.aicoding.security;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.control.aicoding.application.AiCodingTaskProperties;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskValues.ActivationStatus;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskValues.ExecutionStatus;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskEntity;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskHandoffEntity;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskHandoffMapper;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class AiCodingTaskTokenGuard {

    private static final String BEARER_PREFIX = "Bearer ";

    private final AiCodingTaskMapper taskMapper;
    private final AiCodingTaskHandoffMapper handoffMapper;
    private final AiCodingSecretDigester secretDigester;
    private final AiCodingTaskProperties properties;

    public AiCodingTaskHandoffEntity requireTaskAccess(
            String taskId,
            String authorization) {
        String token = bearerToken(authorization);
        String tokenHash = secretDigester.digest(token);
        AiCodingTaskHandoffEntity handoff = handoffMapper.selectOne(
                Wrappers.<AiCodingTaskHandoffEntity>lambdaQuery()
                        .eq(AiCodingTaskHandoffEntity::getTaskId, requireText(taskId, "taskId"))
                        .eq(AiCodingTaskHandoffEntity::getTaskTokenHash, tokenHash)
                        .eq(AiCodingTaskHandoffEntity::getActivationStatus,
                                ActivationStatus.ACTIVATED.name())
                        .last("LIMIT 1"));
        if (handoff == null || handoff.getClosedAt() != null) {
            throw unauthorized("task token is invalid or revoked");
        }
        LocalDateTime now = LocalDateTime.now();
        if (handoff.getTokenExpiresAt() == null
                || !handoff.getTokenExpiresAt().isAfter(now)) {
            throw unauthorized("task token has expired");
        }

        AiCodingTaskEntity task = taskMapper.selectById(taskId.trim());
        if (task == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "task not found");
        }
        ExecutionStatus executionStatus =
                ExecutionStatus.valueOf(task.getExecutionStatus());
        if (!executionStatus.acceptsClientAccess()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    executionStatus.terminal()
                            ? "task is already terminal"
                            : "task is waiting for ReachAI acceptance");
        }

        LocalDateTime leaseExpiresAt =
                now.plus(properties.getConnectionLease());
        int touched = handoffMapper.update(
                null,
                Wrappers.<AiCodingTaskHandoffEntity>lambdaUpdate()
                        .eq(AiCodingTaskHandoffEntity::getHandoffId,
                                handoff.getHandoffId())
                        .eq(AiCodingTaskHandoffEntity::getTaskId, taskId.trim())
                        .eq(AiCodingTaskHandoffEntity::getTaskTokenHash, tokenHash)
                        .eq(AiCodingTaskHandoffEntity::getActivationStatus,
                                ActivationStatus.ACTIVATED.name())
                        .isNull(AiCodingTaskHandoffEntity::getClosedAt)
                        .gt(AiCodingTaskHandoffEntity::getTokenExpiresAt, now)
                        .set(AiCodingTaskHandoffEntity::getLastSeenAt, now)
                        .set(AiCodingTaskHandoffEntity::getLeaseExpiresAt,
                                leaseExpiresAt)
                        .set(AiCodingTaskHandoffEntity::getUpdatedAt, now));
        if (touched != 1) {
            throw unauthorized("task token was revoked while the request was processed");
        }
        handoff.setLastSeenAt(now);
        handoff.setLeaseExpiresAt(leaseExpiresAt);
        handoff.setUpdatedAt(now);
        return handoff;
    }

    private static String bearerToken(String authorization) {
        if (!StringUtils.hasText(authorization)
                || !authorization.regionMatches(
                        true,
                        0,
                        BEARER_PREFIX,
                        0,
                        BEARER_PREFIX.length())) {
            throw unauthorized("Authorization Bearer task token is required");
        }
        String token = authorization.substring(BEARER_PREFIX.length()).trim();
        if (!StringUtils.hasText(token)) {
            throw unauthorized("Authorization Bearer task token is required");
        }
        if (token.length() > 512) {
            throw unauthorized("task token is invalid");
        }
        return token;
    }

    private static ResponseStatusException unauthorized(String reason) {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, reason);
    }

    private static String requireText(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    field + " is required");
        }
        return value.trim();
    }
}
