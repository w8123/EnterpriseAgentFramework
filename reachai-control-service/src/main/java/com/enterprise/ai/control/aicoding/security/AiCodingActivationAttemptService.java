package com.enterprise.ai.control.aicoding.security;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskValues.ActivationStatus;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskHandoffEntity;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskHandoffMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class AiCodingActivationAttemptService {

    private final AiCodingTaskHandoffMapper handoffMapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(AiCodingTaskHandoffEntity handoff, int maxAttempts) {
        LocalDateTime now = LocalDateTime.now();
        int incremented = handoffMapper.update(
                null,
                Wrappers.<AiCodingTaskHandoffEntity>lambdaUpdate()
                        .eq(AiCodingTaskHandoffEntity::getHandoffId,
                                handoff.getHandoffId())
                        .eq(AiCodingTaskHandoffEntity::getActivationStatus,
                                ActivationStatus.ISSUED.name())
                        .setSql("activation_attempts = activation_attempts + 1")
                        .set(AiCodingTaskHandoffEntity::getLastActivationAttemptAt, now)
                        .set(AiCodingTaskHandoffEntity::getUpdatedAt, now));
        if (incremented != 1) {
            return;
        }

        AiCodingTaskHandoffEntity updated =
                handoffMapper.selectById(handoff.getHandoffId());
        if (updated != null
                && updated.getActivationAttempts() != null
                && updated.getActivationAttempts() >= maxAttempts) {
            handoffMapper.update(
                    null,
                    Wrappers.<AiCodingTaskHandoffEntity>lambdaUpdate()
                            .eq(AiCodingTaskHandoffEntity::getHandoffId,
                                    handoff.getHandoffId())
                            .eq(AiCodingTaskHandoffEntity::getActivationStatus,
                                    ActivationStatus.ISSUED.name())
                            .set(AiCodingTaskHandoffEntity::getActivationStatus,
                                    ActivationStatus.REVOKED.name())
                            .set(AiCodingTaskHandoffEntity::getClosedAt, now)
                            .set(AiCodingTaskHandoffEntity::getCloseReason,
                                    "ACTIVATION_ATTEMPTS_EXCEEDED")
                            .set(AiCodingTaskHandoffEntity::getUpdatedAt, now));
        }
    }
}
