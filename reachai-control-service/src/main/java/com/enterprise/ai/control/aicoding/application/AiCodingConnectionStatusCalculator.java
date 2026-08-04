package com.enterprise.ai.control.aicoding.application;

import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ConnectionView;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskValues.ActivationStatus;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskValues.ConnectionStatus;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskValues.ExecutionStatus;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskHandoffEntity;

import java.time.LocalDateTime;

public final class AiCodingConnectionStatusCalculator {

    private AiCodingConnectionStatusCalculator() {
    }

    public static ConnectionView calculate(
            ExecutionStatus executionStatus,
            AiCodingTaskHandoffEntity handoff,
            LocalDateTime now) {
        if (handoff == null) {
            return empty(ConnectionStatus.WAITING_CONNECT, null);
        }
        if (!executionStatus.acceptsClientAccess()) {
            return view(ConnectionStatus.CLOSED, handoff, null);
        }
        if (ActivationStatus.EXPIRED.name().equals(handoff.getActivationStatus())) {
            return view(ConnectionStatus.TIMED_OUT, handoff, "ACTIVATION_EXPIRED");
        }
        if (handoff.getClosedAt() != null
                || ActivationStatus.REVOKED.name().equals(handoff.getActivationStatus())) {
            return view(ConnectionStatus.CLOSED, handoff, null);
        }
        if (ActivationStatus.ISSUED.name().equals(handoff.getActivationStatus())) {
            if (expired(handoff.getActivationExpiresAt(), now)) {
                return view(ConnectionStatus.TIMED_OUT, handoff, "ACTIVATION_EXPIRED");
            }
            return view(ConnectionStatus.WAITING_CONNECT, handoff, null);
        }
        if (expired(handoff.getTokenExpiresAt(), now)) {
            return view(ConnectionStatus.TIMED_OUT, handoff, "TOKEN_EXPIRED");
        }
        if (expired(handoff.getLeaseExpiresAt(), now)) {
            return view(ConnectionStatus.TIMED_OUT, handoff, "LEASE_EXPIRED");
        }
        return view(ConnectionStatus.ACTIVE, handoff, null);
    }

    private static boolean expired(LocalDateTime expiresAt, LocalDateTime now) {
        return expiresAt != null && !expiresAt.isAfter(now);
    }

    private static ConnectionView empty(ConnectionStatus status, String reason) {
        return new ConnectionView(
                status.name(),
                reason,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    private static ConnectionView view(
            ConnectionStatus status,
            AiCodingTaskHandoffEntity handoff,
            String reason) {
        return new ConnectionView(
                status.name(),
                reason,
                handoff.getClientProvider(),
                handoff.getClientSessionRef(),
                handoff.getActivationExpiresAt(),
                handoff.getActivatedAt(),
                handoff.getLastSeenAt(),
                handoff.getLeaseExpiresAt(),
                handoff.getTokenExpiresAt(),
                handoff.getClosedAt());
    }
}
