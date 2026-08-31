package com.enterprise.ai.runtime.automation;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface RuntimeAutomationOccurrenceMapper extends BaseMapper<RuntimeAutomationOccurrenceEntity> {

    @Select("""
            SELECT o.id
            FROM runtime_automation_occurrence o
            JOIN runtime_automation_version v ON v.id = o.automation_version_id
            JOIN runtime_automation a ON a.id = o.automation_id
            WHERE a.status <> 'ARCHIVED'
              AND o.attempt_count < o.max_attempts
              AND (
                (o.status IN ('PENDING', 'RETRY') AND o.available_at <= NOW(6))
                OR (o.status IN ('LEASED', 'RUNNING') AND o.leased_until < NOW(6))
              )
              AND (
                v.concurrency_policy <> 'QUEUE'
                OR NOT EXISTS (
                    SELECT 1 FROM runtime_automation_occurrence active
                    WHERE active.automation_id = o.automation_id
                      AND active.id <> o.id
                      AND active.status IN ('LEASED', 'RUNNING')
                      AND active.leased_until >= NOW(6)
                )
              )
              AND (
                v.concurrency_policy <> 'ALLOW'
                OR (
                    SELECT COUNT(*) FROM runtime_automation_occurrence active
                    WHERE active.automation_id = o.automation_id
                      AND active.id <> o.id
                      AND active.status IN ('LEASED', 'RUNNING')
                      AND active.leased_until >= NOW(6)
                ) < v.max_concurrent_runs
              )
            ORDER BY o.priority DESC, o.available_at ASC, o.id ASC
            LIMIT 1
            """)
    Long findLeaseCandidateId();

    @Update("""
            UPDATE runtime_automation_occurrence
            SET status = 'LEASED', lease_owner = #{leaseOwner}, lease_token = #{leaseToken},
                leased_until = #{leasedUntil}, attempt_count = attempt_count + 1,
                updated_at = NOW(6)
            WHERE id = #{id}
              AND attempt_count < max_attempts
              AND (
                (status IN ('PENDING', 'RETRY') AND available_at <= NOW(6))
                OR (status IN ('LEASED', 'RUNNING') AND leased_until < NOW(6))
              )
            """)
    int claim(@Param("id") Long id,
              @Param("leaseOwner") String leaseOwner,
              @Param("leaseToken") String leaseToken,
              @Param("leasedUntil") LocalDateTime leasedUntil);

    @Update("""
            UPDATE runtime_automation_occurrence
            SET status = 'RUNNING', trace_id = #{traceId}, started_at = COALESCE(started_at, NOW(6)),
                updated_at = NOW(6)
            WHERE id = #{id} AND status = 'LEASED' AND lease_token = #{leaseToken}
            """)
    int markRunning(@Param("id") Long id,
                    @Param("leaseToken") String leaseToken,
                    @Param("traceId") String traceId);

    @Update("""
            UPDATE runtime_automation_occurrence
            SET leased_until = #{leasedUntil}, updated_at = NOW(6)
            WHERE id = #{id} AND status IN ('LEASED', 'RUNNING') AND lease_token = #{leaseToken}
            """)
    int renew(@Param("id") Long id,
              @Param("leaseToken") String leaseToken,
              @Param("leasedUntil") LocalDateTime leasedUntil);

    @Update("""
            UPDATE runtime_automation_occurrence
            SET interaction_id = #{interactionId}, updated_at = NOW(6)
            WHERE id = #{id} AND lease_token = #{leaseToken}
            """)
    int recordInteraction(@Param("id") Long id,
                          @Param("leaseToken") String leaseToken,
                          @Param("interactionId") String interactionId);

    @Update("""
            UPDATE runtime_automation_occurrence
            SET status = 'SUCCEEDED', lease_owner = NULL, lease_token = NULL, leased_until = NULL,
                last_error_code = NULL, last_error_message = NULL,
                completed_at = NOW(6), updated_at = NOW(6)
            WHERE id = #{id} AND status = 'RUNNING' AND lease_token = #{leaseToken}
            """)
    int complete(@Param("id") Long id, @Param("leaseToken") String leaseToken);

    @Update("""
            UPDATE runtime_automation_occurrence
            SET status = #{nextStatus}, available_at = #{availableAt},
                lease_owner = NULL, lease_token = NULL, leased_until = NULL,
                last_error_code = #{errorCode}, last_error_message = #{errorMessage},
                completed_at = CASE WHEN #{nextStatus} IN ('FAILED', 'DEAD', 'CANCELLED', 'SKIPPED')
                                    THEN NOW(6) ELSE NULL END,
                updated_at = NOW(6)
            WHERE id = #{id} AND status IN ('LEASED', 'RUNNING') AND lease_token = #{leaseToken}
            """)
    int release(@Param("id") Long id,
                @Param("leaseToken") String leaseToken,
                @Param("nextStatus") String nextStatus,
                @Param("availableAt") LocalDateTime availableAt,
                @Param("errorCode") String errorCode,
                @Param("errorMessage") String errorMessage);

    @Update("""
            UPDATE runtime_automation_occurrence
            SET status = 'PENDING', available_at = #{availableAt},
                lease_owner = NULL, lease_token = NULL, leased_until = NULL,
                attempt_count = GREATEST(0, attempt_count - 1), updated_at = NOW(6)
            WHERE id = #{id} AND status = 'LEASED' AND lease_token = #{leaseToken}
            """)
    int defer(@Param("id") Long id,
              @Param("leaseToken") String leaseToken,
              @Param("availableAt") LocalDateTime availableAt);

    @Update("""
            UPDATE runtime_automation_occurrence
            SET status = 'SKIPPED', last_error_code = #{errorCode}, last_error_message = #{errorMessage},
                completed_at = NOW(6), updated_at = NOW(6)
            WHERE id = #{id} AND status IN ('PENDING', 'RETRY')
            """)
    int skipPending(@Param("id") Long id,
                    @Param("errorCode") String errorCode,
                    @Param("errorMessage") String errorMessage);

    @Update("""
            UPDATE runtime_automation_occurrence
            SET status = 'CANCELLED', last_error_code = 'AUTOMATION_CANCELLED',
                last_error_message = #{reason}, completed_at = NOW(6), updated_at = NOW(6)
            WHERE id = #{id} AND status IN ('PENDING', 'RETRY')
            """)
    int cancelPending(@Param("id") Long id, @Param("reason") String reason);

    @Update("""
            UPDATE runtime_automation_occurrence
            SET status = 'CANCELLED', last_error_code = 'AUTOMATION_DEACTIVATED',
                last_error_message = 'Automation was paused or archived',
                completed_at = NOW(6), updated_at = NOW(6)
            WHERE automation_id = #{automationId} AND status IN ('PENDING', 'RETRY')
            """)
    int cancelPendingForAutomation(@Param("automationId") Long automationId);

    @Select("""
            SELECT COUNT(*)
            FROM runtime_automation_occurrence
            WHERE automation_id = #{automationId}
              AND id <> #{occurrenceId}
              AND status IN ('LEASED', 'RUNNING')
              AND leased_until >= NOW(6)
            """)
    int countOtherActive(@Param("automationId") Long automationId,
                         @Param("occurrenceId") Long occurrenceId);

    @Update("""
            UPDATE runtime_automation_occurrence
            SET status = 'DEAD', lease_owner = NULL, lease_token = NULL, leased_until = NULL,
                last_error_code = 'AUTOMATION_LEASE_EXHAUSTED',
                last_error_message = 'Execution lease expired after the final allowed attempt',
                completed_at = NOW(6), updated_at = NOW(6)
            WHERE status IN ('LEASED', 'RUNNING') AND leased_until < NOW(6)
              AND attempt_count >= max_attempts
            """)
    int markExpiredExhausted();
}
