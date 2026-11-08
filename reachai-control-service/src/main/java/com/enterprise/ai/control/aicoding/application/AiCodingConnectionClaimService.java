package com.enterprise.ai.control.aicoding.application;

import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ActivationClient;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ActivationCommand;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ActivationView;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskValues.ActivationStatus;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskEntity;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskHandoffEntity;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskMapper;
import com.enterprise.ai.control.aicoding.security.AiCodingTaskTokenGuard;
import com.enterprise.ai.control.identity.PlatformAiConnectionService.ConnectionIdentity;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;

/**
 * Lets a person's own platform AI connection (the one set up once on 「AI 工具接入」) take over an AI Coding task,
 * instead of pasting a per-task Windows PowerShell handoff.
 *
 * <p>Handing a task to an AI stays a human step: {@link #claim} needs the one-time code that only a person can issue
 * on the console, and it goes through the same activation checks (expiry, attempt limit, single use) as the
 * PowerShell handoff. The issued task token is never returned; the claim is bound to the connection through the
 * handoff's client session reference, and later MCP calls are checked with
 * {@link AiCodingTaskTokenGuard#requireConnectionTaskAccess}.</p>
 */
@Service
@RequiredArgsConstructor
public class AiCodingConnectionClaimService {

    private final AiCodingTaskMapper taskMapper;
    private final AiCodingHandoffApplicationService handoffService;

    public record ConnectionClaimView(
            String taskId,
            String executionStatus,
            LocalDateTime leaseExpiresAt,
            LocalDateTime tokenExpiresAt) {
    }

    /** Claims a task with the one-time code a person issued on the console. */
    @Transactional(noRollbackFor = ResponseStatusException.class)
    public ConnectionClaimView claim(String taskId, String claimCode, ConnectionIdentity identity, String publicBaseUrl) {
        requireIdentity(identity);
        AiCodingTaskEntity task = requireTask(taskId);
        if (!StringUtils.hasText(claimCode)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "claimCode is required");
        }
        AiCodingTaskHandoffEntity handoff = handoffService.latest(task.getTaskId());
        if (handoff == null || handoff.getClosedAt() != null
                || !ActivationStatus.ISSUED.name().equals(handoff.getActivationStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "no claim code is waiting for this task; issue a new one on the console");
        }
        return activate(task, handoff.getHandoffId(), claimCode.trim(), identity, publicBaseUrl);
    }

    /**
     * Claims a task that this very account just created over MCP, without a person issuing a code. Only the
     * creator may do this, and only while nobody holds the task yet.
     */
    @Transactional(noRollbackFor = ResponseStatusException.class)
    public ConnectionClaimView claimAsCreator(String taskId, ConnectionIdentity identity, String publicBaseUrl) {
        requireIdentity(identity);
        AiCodingTaskEntity task = requireTask(taskId);
        String username = identity.session().user().getUsername();
        if (!StringUtils.hasText(username) || !username.equals(task.getCreatedBy())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "only the task creator can claim it without a code");
        }
        AiCodingTaskHandoffEntity current = handoffService.latest(task.getTaskId());
        if (current != null && current.getClosedAt() == null
                && ActivationStatus.ACTIVATED.name().equals(current.getActivationStatus())) {
            boolean expiredOwnClaim = AiCodingTaskTokenGuard.connectionSessionRef(identity.connectionId())
                    .equals(current.getClientSessionRef())
                    && current.getTokenExpiresAt() != null
                    && !current.getTokenExpiresAt().isAfter(LocalDateTime.now());
            if (!expiredOwnClaim) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "task is already held by an AI client");
            }
        }
        AiCodingHandoffApplicationService.ConnectionActivation issued =
                handoffService.issueForConnection(task.getTaskId(), username);
        return activate(task, issued.handoffId(), issued.activationCode(), identity, publicBaseUrl);
    }

    private ConnectionClaimView activate(AiCodingTaskEntity task, String handoffId, String code,
                                         ConnectionIdentity identity, String publicBaseUrl) {
        ActivationView activation = handoffService.activateForConnection(
                handoffId,
                new ActivationCommand(
                        AiCodingHandoffApplicationService.ACTIVATION_SCHEMA,
                        code,
                        new ActivationClient(
                                task.getExecutorProvider(),
                                AiCodingTaskTokenGuard.connectionSessionRef(identity.connectionId()))),
                publicBaseUrl);
        AiCodingTaskEntity refreshed = requireTask(task.getTaskId());
        return new ConnectionClaimView(
                refreshed.getTaskId(),
                refreshed.getExecutionStatus(),
                activation.leaseExpiresAt(),
                activation.tokenExpiresAt());
    }

    private AiCodingTaskEntity requireTask(String taskId) {
        if (!StringUtils.hasText(taskId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "taskId is required");
        }
        AiCodingTaskEntity task = taskMapper.selectById(taskId.trim());
        if (task == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "task not found");
        }
        if ("MANAGED_SANDBOX".equalsIgnoreCase(task.getExecutionMode())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "managed sandbox tasks run inside ReachAI");
        }
        return task;
    }

    private static void requireIdentity(ConnectionIdentity identity) {
        if (identity == null || identity.connectionId() == null || identity.session() == null
                || identity.session().user() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "platform AI connection is required");
        }
    }
}
