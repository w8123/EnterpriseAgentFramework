package com.enterprise.ai.runtime.managed;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface ManagedExecutionMapper extends BaseMapper<ManagedExecutionEntity> {

    @Select("""
            SELECT COUNT(*)
            FROM runtime_managed_execution
            WHERE status IN ('REQUESTED', 'QUEUED', 'PROVISIONING', 'RUNNING',
                             'WAITING_APPROVAL', 'WAITING_USER', 'FINALIZING', 'CANCELLING')
            """)
    int countActive();

    @Select("""
            SELECT id
            FROM runtime_managed_execution
            WHERE status = 'QUEUED'
              AND worker_token_digest IS NULL
              AND provision_attempt_count < provision_max_attempts
              AND provision_available_at <= #{now}
            ORDER BY priority DESC, id ASC
            LIMIT 1
            """)
    Long findProvisionCandidateId(@Param("now") LocalDateTime now);

    @Update("""
            UPDATE runtime_managed_execution
            SET worker_token_digest = #{tokenDigest},
                worker_token_expires_at = #{tokenExpiresAt},
                provision_attempt_count = provision_attempt_count + 1,
                version = version + 1,
                updated_at = #{now}
            WHERE id = #{id}
              AND status = 'QUEUED'
              AND worker_token_digest IS NULL
              AND provision_attempt_count < provision_max_attempts
              AND provision_available_at <= #{now}
            """)
    int issueWorkerToken(@Param("id") Long id,
                         @Param("tokenDigest") String tokenDigest,
                         @Param("tokenExpiresAt") LocalDateTime tokenExpiresAt,
                         @Param("now") LocalDateTime now);

    @Update("""
            UPDATE runtime_managed_execution
            SET worker_token_digest = NULL,
                worker_token_expires_at = NULL,
                provision_available_at = #{availableAt},
                status = CASE WHEN provision_attempt_count >= provision_max_attempts THEN 'FAILED' ELSE 'QUEUED' END,
                error_code = 'MANAGED_EXECUTION_PROVISION_FAILED',
                error_message = #{errorMessage},
                completed_at = CASE WHEN provision_attempt_count >= provision_max_attempts THEN #{now} ELSE completed_at END,
                version = version + 1,
                updated_at = #{now}
            WHERE execution_id = #{executionId}
              AND status = 'QUEUED'
              AND worker_token_digest = #{tokenDigest}
              AND lease_owner IS NULL
            """)
    int releaseProvisioning(@Param("executionId") String executionId,
                            @Param("tokenDigest") String tokenDigest,
                            @Param("errorMessage") String errorMessage,
                            @Param("now") LocalDateTime now,
                             @Param("availableAt") LocalDateTime availableAt);

    @Update("""
            UPDATE runtime_managed_execution
            SET sandbox_ref = #{sandboxRef},
                updated_at = #{now}
            WHERE execution_id = #{executionId}
              AND status = 'QUEUED'
              AND worker_token_digest = #{tokenDigest}
              AND sandbox_ref IS NULL
            """)
    int recordSandboxDispatched(@Param("executionId") String executionId,
                                @Param("tokenDigest") String tokenDigest,
                                @Param("sandboxRef") String sandboxRef,
                                @Param("now") LocalDateTime now);

    @Update("""
            UPDATE runtime_managed_execution
            SET status = 'FAILED',
                sandbox_ref = #{sandboxRef},
                error_code = 'MANAGED_SANDBOX_PROVISION_OUTCOME_UNKNOWN',
                error_message = 'Managed Sandbox provisioning outcome is unknown',
                worker_token_digest = NULL,
                worker_token_expires_at = NULL,
                completed_at = #{now},
                cleanup_status = 'PENDING',
                cleanup_available_at = #{now},
                version = version + 1,
                updated_at = #{now}
            WHERE execution_id = #{executionId}
              AND status = 'QUEUED'
              AND worker_token_digest = #{tokenDigest}
              AND lease_owner IS NULL
            """)
    int markProvisionOutcomeUnknown(@Param("executionId") String executionId,
                                    @Param("tokenDigest") String tokenDigest,
                                    @Param("sandboxRef") String sandboxRef,
                                    @Param("now") LocalDateTime now);

    @Update("""
            UPDATE runtime_managed_execution
            SET worker_token_digest = NULL,
                worker_token_expires_at = NULL,
                provision_available_at = #{now},
                version = version + 1,
                updated_at = #{now}
            WHERE status = 'QUEUED'
              AND lease_owner IS NULL
              AND worker_token_digest IS NOT NULL
              AND worker_token_expires_at <= #{now}
            """)
    int recoverExpiredProvisioningTokens(@Param("now") LocalDateTime now);

    @Update("""
            UPDATE runtime_managed_execution
            SET status = 'PROVISIONING',
                lease_owner = #{workerId},
                lease_expires_at = #{leaseExpiresAt},
                last_heartbeat_at = #{now},
                started_at = COALESCE(started_at, #{now}),
                version = version + 1,
                updated_at = #{now}
            WHERE execution_id = #{executionId}
              AND worker_token_digest = #{tokenDigest}
              AND worker_token_expires_at > #{now}
              AND status = 'QUEUED'
              AND lease_owner IS NULL
            """)
    int claim(@Param("executionId") String executionId,
              @Param("tokenDigest") String tokenDigest,
              @Param("workerId") String workerId,
              @Param("now") LocalDateTime now,
              @Param("leaseExpiresAt") LocalDateTime leaseExpiresAt);

    @Update("""
            UPDATE runtime_managed_execution
            SET lease_expires_at = #{leaseExpiresAt},
                last_heartbeat_at = #{now},
                version = version + 1,
                updated_at = #{now}
            WHERE execution_id = #{executionId}
              AND worker_token_digest = #{tokenDigest}
              AND worker_token_expires_at > #{now}
              AND lease_owner = #{workerId}
              AND status IN ('PROVISIONING', 'RUNNING', 'WAITING_APPROVAL',
                             'WAITING_USER', 'FINALIZING', 'CANCELLING', 'FAILED', 'CANCELLED')
            """)
    int heartbeat(@Param("executionId") String executionId,
                  @Param("tokenDigest") String tokenDigest,
                  @Param("workerId") String workerId,
                  @Param("now") LocalDateTime now,
                  @Param("leaseExpiresAt") LocalDateTime leaseExpiresAt);

    @Update("""
            UPDATE runtime_managed_execution
            SET pending_approval_request_id = #{approvalRequestId},
                pending_interaction_id = #{interactionId},
                approval_decision = NULL,
                approval_decided_at = NULL,
                approval_count = approval_count + 1,
                version = version + 1,
                updated_at = #{now}
            WHERE execution_id = #{executionId}
              AND status = 'WAITING_APPROVAL'
              AND pending_approval_request_id IS NULL
              AND pending_interaction_id IS NULL
            """)
    int openApproval(@Param("executionId") String executionId,
                     @Param("approvalRequestId") String approvalRequestId,
                     @Param("interactionId") String interactionId,
                     @Param("now") LocalDateTime now);

    @Update("""
            UPDATE runtime_managed_execution
            SET approval_decision = #{decision},
                approval_decided_at = #{now},
                command_sequence = command_sequence + 1,
                version = version + 1,
                updated_at = #{now}
            WHERE execution_id = #{executionId}
              AND status = 'WAITING_APPROVAL'
              AND pending_approval_request_id = #{approvalRequestId}
              AND pending_interaction_id = #{interactionId}
              AND approval_decision IS NULL
            """)
    int resolveApproval(@Param("executionId") String executionId,
                        @Param("approvalRequestId") String approvalRequestId,
                        @Param("interactionId") String interactionId,
                        @Param("decision") String decision,
                        @Param("now") LocalDateTime now);

    @Update("""
            UPDATE runtime_managed_execution
            SET pending_approval_request_id = NULL,
                pending_interaction_id = NULL,
                approval_decision = NULL,
                approval_decided_at = NULL,
                version = version + 1,
                updated_at = #{now}
            WHERE execution_id = #{executionId}
              AND pending_approval_request_id = #{approvalRequestId}
            """)
    int closeApproval(@Param("executionId") String executionId,
                      @Param("approvalRequestId") String approvalRequestId,
                      @Param("now") LocalDateTime now);

    @Update("""
            UPDATE runtime_managed_execution
            SET last_event_sequence = #{nextSequence},
                status = #{nextStatus},
                lease_expires_at = #{leaseExpiresAt},
                last_heartbeat_at = #{now},
                finalizing_at = CASE WHEN #{nextStatus} = 'FINALIZING'
                                     THEN COALESCE(finalizing_at, #{now}) ELSE finalizing_at END,
                completed_at = CASE WHEN #{nextStatus} IN ('FAILED', 'TIMED_OUT', 'CANCELLED')
                                    THEN COALESCE(completed_at, #{now}) ELSE completed_at END,
                version = version + 1,
                updated_at = #{now}
            WHERE execution_id = #{executionId}
              AND worker_token_digest = #{tokenDigest}
              AND worker_token_expires_at > #{now}
              AND lease_owner = #{workerId}
              AND last_event_sequence = #{expectedSequence}
              AND status NOT IN ('SUCCEEDED', 'TIMED_OUT')
            """)
    int advanceEvent(@Param("executionId") String executionId,
                     @Param("tokenDigest") String tokenDigest,
                     @Param("workerId") String workerId,
                     @Param("expectedSequence") int expectedSequence,
                     @Param("nextSequence") int nextSequence,
                     @Param("nextStatus") String nextStatus,
                     @Param("now") LocalDateTime now,
                     @Param("leaseExpiresAt") LocalDateTime leaseExpiresAt);

    @Update("""
            UPDATE runtime_managed_execution
            SET status = #{nextStatus},
                error_code = #{errorCode},
                error_message = #{errorMessage},
                lease_owner = NULL,
                lease_expires_at = NULL,
                completed_at = CASE WHEN #{nextStatus} IN ('SUCCEEDED', 'FAILED', 'CANCELLED')
                                    THEN COALESCE(completed_at, #{now}) ELSE completed_at END,
                finalizing_at = CASE WHEN #{nextStatus} = 'FINALIZING'
                                     THEN COALESCE(finalizing_at, #{now}) ELSE finalizing_at END,
                version = version + 1,
                updated_at = #{now}
            WHERE execution_id = #{executionId}
              AND worker_token_digest = #{tokenDigest}
              AND lease_owner = #{workerId}
              AND status IN ('PROVISIONING', 'RUNNING', 'WAITING_APPROVAL', 'WAITING_USER',
                             'FINALIZING', 'CANCELLING', 'FAILED', 'CANCELLED')
            """)
    int completeByWorker(@Param("executionId") String executionId,
                         @Param("tokenDigest") String tokenDigest,
                         @Param("workerId") String workerId,
                         @Param("nextStatus") String nextStatus,
                         @Param("errorCode") String errorCode,
                         @Param("errorMessage") String errorMessage,
                         @Param("now") LocalDateTime now);

    @Update("""
            UPDATE runtime_managed_execution
            SET status = CASE WHEN status IN ('REQUESTED', 'QUEUED') THEN 'CANCELLED' ELSE 'CANCELLING' END,
                cancel_requested_at = #{now},
                command_sequence = command_sequence + 1,
                completed_at = CASE WHEN status IN ('REQUESTED', 'QUEUED') THEN #{now} ELSE completed_at END,
                error_code = 'MANAGED_EXECUTION_CANCEL_REQUESTED',
                error_message = #{reason},
                worker_token_digest = CASE WHEN status IN ('REQUESTED', 'QUEUED') THEN NULL ELSE worker_token_digest END,
                worker_token_expires_at = CASE WHEN status IN ('REQUESTED', 'QUEUED') THEN NULL ELSE worker_token_expires_at END,
                version = version + 1,
                updated_at = #{now}
            WHERE execution_id = #{executionId}
              AND status IN ('REQUESTED', 'QUEUED', 'PROVISIONING', 'RUNNING',
                             'WAITING_APPROVAL', 'WAITING_USER', 'FINALIZING')
            """)
    int requestCancel(@Param("executionId") String executionId,
                      @Param("reason") String reason,
                      @Param("now") LocalDateTime now);

    @Select("""
            SELECT id
            FROM runtime_managed_execution
            WHERE lease_expires_at < #{now}
              AND status IN ('PROVISIONING', 'RUNNING', 'WAITING_APPROVAL',
                             'WAITING_USER', 'FINALIZING', 'CANCELLING')
            ORDER BY lease_expires_at ASC, id ASC
            LIMIT #{limit}
            """)
    List<Long> findExpiredLeaseIds(@Param("now") LocalDateTime now,
                                   @Param("limit") int limit);

    @Update("""
            UPDATE runtime_managed_execution
            SET status = 'TIMED_OUT',
                error_code = 'MANAGED_EXECUTION_LEASE_EXPIRED',
                error_message = 'Managed Executor worker lease expired',
                lease_owner = NULL,
                lease_expires_at = NULL,
                worker_token_digest = NULL,
                worker_token_expires_at = NULL,
                completed_at = #{now},
                version = version + 1,
                updated_at = #{now}
            WHERE id = #{id}
              AND lease_expires_at < #{now}
              AND status IN ('PROVISIONING', 'RUNNING', 'WAITING_APPROVAL',
                             'WAITING_USER', 'FINALIZING', 'CANCELLING')
            """)
    int markTimedOut(@Param("id") Long id, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE runtime_managed_execution
            SET status = 'SUCCEEDED',
                worker_token_digest = NULL,
                worker_token_expires_at = NULL,
                completed_at = #{now},
                version = version + 1,
                updated_at = #{now}
            WHERE execution_id = #{executionId}
              AND status = 'FINALIZING'
            """)
    int markArtifactsVerified(@Param("executionId") String executionId,
                              @Param("now") LocalDateTime now);

    @Update("""
            UPDATE runtime_managed_execution
            SET status = 'FAILED',
                error_code = 'MANAGED_ARTIFACT_REJECTED',
                error_message = 'Managed execution artifact verification failed',
                worker_token_digest = NULL,
                worker_token_expires_at = NULL,
                completed_at = #{now},
                version = version + 1,
                updated_at = #{now}
            WHERE execution_id = #{executionId}
              AND status = 'FINALIZING'
            """)
    int markArtifactRejected(@Param("executionId") String executionId,
                             @Param("now") LocalDateTime now);

    @Select("""
            SELECT id
            FROM runtime_managed_execution
            WHERE status IN ('SUCCEEDED', 'FAILED', 'TIMED_OUT', 'CANCELLED')
              AND (
                    cleanup_status IN ('PENDING', 'FAILED')
                    OR (cleanup_status = 'RUNNING' AND cleanup_available_at <= #{now})
                  )
              AND cleanup_available_at <= #{now}
            ORDER BY cleanup_available_at ASC, id ASC
            LIMIT #{limit}
            """)
    List<Long> findCleanupCandidateIds(@Param("now") LocalDateTime now,
                                       @Param("limit") int limit);

    @Update("""
            UPDATE runtime_managed_execution
            SET cleanup_status = 'RUNNING',
                cleanup_attempt_count = cleanup_attempt_count + 1,
                cleanup_available_at = #{leaseExpiresAt},
                cleanup_error = NULL,
                updated_at = #{now}
            WHERE id = #{id}
              AND status IN ('SUCCEEDED', 'FAILED', 'TIMED_OUT', 'CANCELLED')
              AND (
                    cleanup_status IN ('PENDING', 'FAILED')
                    OR (cleanup_status = 'RUNNING' AND cleanup_available_at <= #{now})
                  )
              AND cleanup_available_at <= #{now}
            """)
    int claimCleanup(@Param("id") Long id,
                     @Param("now") LocalDateTime now,
                     @Param("leaseExpiresAt") LocalDateTime leaseExpiresAt);

    @Update("""
            UPDATE runtime_managed_execution
            SET cleanup_status = 'COMPLETED',
                cleanup_available_at = #{now},
                cleanup_error = NULL,
                updated_at = #{now}
            WHERE id = #{id}
              AND cleanup_status = 'RUNNING'
            """)
    int markCleanupCompleted(@Param("id") Long id,
                             @Param("now") LocalDateTime now);

    @Update("""
            UPDATE runtime_managed_execution
            SET cleanup_status = 'FAILED',
                cleanup_available_at = #{availableAt},
                cleanup_error = #{errorMessage},
                updated_at = #{now}
            WHERE id = #{id}
              AND cleanup_status = 'RUNNING'
            """)
    int markCleanupFailed(@Param("id") Long id,
                          @Param("errorMessage") String errorMessage,
                          @Param("availableAt") LocalDateTime availableAt,
                          @Param("now") LocalDateTime now);
}
