package com.enterprise.ai.runtime.eval;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface RuntimeEvalTaskMapper extends BaseMapper<RuntimeEvalTaskEntity> {

    @Select("""
            SELECT id
            FROM runtime_eval_task
            WHERE (status IN ('PENDING', 'RETRY')
                     AND attempt_count < max_attempts
                     AND available_at <= NOW())
               OR (status = 'LEASED' AND leased_until < NOW())
            ORDER BY priority DESC, id ASC
            LIMIT 1
            """)
    Long findLeaseCandidateId();

    @Update("""
            UPDATE runtime_eval_task
            SET status = 'LEASED',
                lease_owner = #{leaseOwner},
                lease_token = #{leaseToken},
                leased_until = #{leasedUntil},
                attempt_count = attempt_count + 1,
                updated_at = NOW()
            WHERE id = #{taskId}
              AND attempt_count < max_attempts
              AND (
                (status IN ('PENDING', 'RETRY') AND available_at <= NOW())
                OR (status = 'LEASED' AND leased_until < NOW())
              )
            """)
    int claim(@Param("taskId") Long taskId,
              @Param("leaseOwner") String leaseOwner,
              @Param("leaseToken") String leaseToken,
              @Param("leasedUntil") LocalDateTime leasedUntil);

    /**
     * Takes ownership of a lease that expired after its final allowed attempt. The worker uses
     * this token only to atomically mark the item/task DEAD; it must not execute the item again.
     */
    @Update("""
            UPDATE runtime_eval_task
            SET lease_owner = #{leaseOwner},
                lease_token = #{leaseToken},
                leased_until = #{leasedUntil},
                updated_at = NOW()
            WHERE id = #{taskId}
              AND status = 'LEASED'
              AND leased_until < NOW()
              AND attempt_count >= max_attempts
            """)
    int claimExpiredExhausted(@Param("taskId") Long taskId,
                              @Param("leaseOwner") String leaseOwner,
                              @Param("leaseToken") String leaseToken,
                              @Param("leasedUntil") LocalDateTime leasedUntil);

    @Update("""
            UPDATE runtime_eval_task
            SET leased_until = #{leasedUntil}, updated_at = NOW()
            WHERE id = #{taskId}
              AND status = 'LEASED'
              AND lease_token = #{leaseToken}
            """)
    int renew(@Param("taskId") Long taskId,
              @Param("leaseToken") String leaseToken,
              @Param("leasedUntil") LocalDateTime leasedUntil);

    @Update("""
            UPDATE runtime_eval_task
            SET status = 'COMPLETED',
                lease_owner = NULL,
                lease_token = NULL,
                leased_until = NULL,
                completed_at = NOW(),
                updated_at = NOW()
            WHERE id = #{taskId}
              AND status = 'LEASED'
              AND lease_token = #{leaseToken}
            """)
    int complete(@Param("taskId") Long taskId,
                 @Param("leaseToken") String leaseToken);

    @Update("""
            UPDATE runtime_eval_task
            SET status = #{nextStatus},
                available_at = #{availableAt},
                lease_owner = NULL,
                lease_token = NULL,
                leased_until = NULL,
                last_error_code = #{errorCode},
                last_error_message = #{errorMessage},
                completed_at = CASE WHEN #{nextStatus} IN ('DEAD', 'CANCELLED') THEN NOW() ELSE completed_at END,
                updated_at = NOW()
            WHERE id = #{taskId}
              AND status = 'LEASED'
              AND lease_token = #{leaseToken}
            """)
    int release(@Param("taskId") Long taskId,
                @Param("leaseToken") String leaseToken,
                @Param("nextStatus") String nextStatus,
                @Param("availableAt") LocalDateTime availableAt,
                @Param("errorCode") String errorCode,
                @Param("errorMessage") String errorMessage);

    @Update("""
            UPDATE runtime_eval_task
            SET status = 'CANCELLED', completed_at = NOW(), updated_at = NOW()
            WHERE experiment_id = #{experimentId}
              AND status IN ('PENDING', 'RETRY')
            """)
    int cancelPending(@Param("experimentId") Long experimentId);
}
