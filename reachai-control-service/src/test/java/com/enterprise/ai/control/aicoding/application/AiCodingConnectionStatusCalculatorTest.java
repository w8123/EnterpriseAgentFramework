package com.enterprise.ai.control.aicoding.application;

import com.enterprise.ai.control.aicoding.domain.AiCodingTaskValues.ActivationStatus;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskValues.ExecutionStatus;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskHandoffEntity;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AiCodingConnectionStatusCalculatorTest {

    private static final LocalDateTime NOW =
            LocalDateTime.of(2026, 7, 25, 12, 0);

    @Test
    void distinguishesWaitingActiveTimedOutAndClosed() {
        assertEquals(
                "WAITING_CONNECT",
                AiCodingConnectionStatusCalculator.calculate(
                        ExecutionStatus.READY,
                        null,
                        NOW).status());

        AiCodingTaskHandoffEntity issued = handoff(
                ActivationStatus.ISSUED,
                NOW.plusMinutes(1),
                null,
                null);
        assertEquals(
                "WAITING_CONNECT",
                AiCodingConnectionStatusCalculator.calculate(
                        ExecutionStatus.READY,
                        issued,
                        NOW).status());

        AiCodingTaskHandoffEntity active = handoff(
                ActivationStatus.ACTIVATED,
                NOW.minusMinutes(1),
                NOW.plusMinutes(5),
                NOW.plusHours(1));
        assertEquals(
                "ACTIVE",
                AiCodingConnectionStatusCalculator.calculate(
                        ExecutionStatus.RUNNING,
                        active,
                        NOW).status());

        active.setLeaseExpiresAt(NOW);
        assertEquals(
                "LEASE_EXPIRED",
                AiCodingConnectionStatusCalculator.calculate(
                        ExecutionStatus.RUNNING,
                        active,
                        NOW).timeoutReason());

        AiCodingTaskHandoffEntity expired = handoff(
                ActivationStatus.EXPIRED,
                NOW,
                null,
                null);
        expired.setClosedAt(NOW);
        assertEquals(
                "ACTIVATION_EXPIRED",
                AiCodingConnectionStatusCalculator.calculate(
                        ExecutionStatus.READY,
                        expired,
                        NOW).timeoutReason());

        assertEquals(
                "CLOSED",
                AiCodingConnectionStatusCalculator.calculate(
                        ExecutionStatus.COMPLETED,
                        active,
                        NOW).status());
        assertEquals(
                "CLOSED",
                AiCodingConnectionStatusCalculator.calculate(
                        ExecutionStatus.ACCEPTANCE_READY,
                        active,
                        NOW).status());
    }

    private static AiCodingTaskHandoffEntity handoff(
            ActivationStatus status,
            LocalDateTime activationExpiresAt,
            LocalDateTime leaseExpiresAt,
            LocalDateTime tokenExpiresAt) {
        AiCodingTaskHandoffEntity entity =
                new AiCodingTaskHandoffEntity();
        entity.setActivationStatus(status.name());
        entity.setActivationExpiresAt(activationExpiresAt);
        entity.setLeaseExpiresAt(leaseExpiresAt);
        entity.setTokenExpiresAt(tokenExpiresAt);
        return entity;
    }
}
