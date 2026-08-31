package com.enterprise.ai.control.a2a.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface A2aOutboxMapper extends BaseMapper<A2aOutboxEntity> {

    @Select("""
            SELECT id FROM control_a2a_outbox
            WHERE status IN ('PENDING', 'RETRY')
              AND next_attempt_at <= #{now}
            ORDER BY next_attempt_at ASC, id ASC
            LIMIT #{limit}
            """)
    List<Long> findDueIds(@Param("now") LocalDateTime now, @Param("limit") int limit);

    @Update("""
            UPDATE control_a2a_outbox
            SET status = 'CLAIMED', lease_owner = #{workerId}, lease_until = #{leaseUntil}
            WHERE id = #{id}
              AND status IN ('PENDING', 'RETRY')
              AND next_attempt_at <= #{now}
            """)
    int claim(@Param("id") long id,
              @Param("workerId") String workerId,
              @Param("now") LocalDateTime now,
              @Param("leaseUntil") LocalDateTime leaseUntil);

    @Update("""
            UPDATE control_a2a_outbox
            SET status = 'DELIVERED', delivered_at = #{deliveredAt},
                lease_owner = NULL, lease_until = NULL,
                last_error_code = NULL, last_error_summary = NULL
            WHERE id = #{id} AND status = 'CLAIMED' AND lease_owner = #{workerId}
            """)
    int markDelivered(@Param("id") long id,
                      @Param("workerId") String workerId,
                      @Param("deliveredAt") LocalDateTime deliveredAt);

    @Update("""
            UPDATE control_a2a_outbox
            SET status = 'RETRY', attempt_count = #{attemptCount},
                next_attempt_at = #{nextAttemptAt}, lease_owner = NULL, lease_until = NULL,
                last_error_code = #{errorCode}, last_error_summary = #{errorSummary}
            WHERE id = #{id} AND status = 'CLAIMED' AND lease_owner = #{workerId}
            """)
    int markRetry(@Param("id") long id,
                  @Param("workerId") String workerId,
                  @Param("attemptCount") int attemptCount,
                  @Param("nextAttemptAt") LocalDateTime nextAttemptAt,
                  @Param("errorCode") String errorCode,
                  @Param("errorSummary") String errorSummary);

    @Update("""
            UPDATE control_a2a_outbox
            SET status = 'DEAD', attempt_count = #{attemptCount},
                lease_owner = NULL, lease_until = NULL,
                last_error_code = #{errorCode}, last_error_summary = #{errorSummary}
            WHERE id = #{id} AND status = 'CLAIMED' AND lease_owner = #{workerId}
            """)
    int markDead(@Param("id") long id,
                 @Param("workerId") String workerId,
                 @Param("attemptCount") int attemptCount,
                 @Param("errorCode") String errorCode,
                 @Param("errorSummary") String errorSummary);

    @Update("""
            UPDATE control_a2a_outbox
            SET lease_until = #{leaseUntil}
            WHERE id = #{id} AND status = 'CLAIMED' AND lease_owner = #{workerId}
            """)
    int renewLease(@Param("id") long id,
                   @Param("workerId") String workerId,
                   @Param("leaseUntil") LocalDateTime leaseUntil);

    @Select("""
            SELECT id FROM control_a2a_outbox
            WHERE status = 'CLAIMED' AND lease_until < #{now}
            ORDER BY lease_until ASC, id ASC
            LIMIT #{limit}
            """)
    List<Long> findExpiredClaimIds(@Param("now") LocalDateTime now, @Param("limit") int limit);

    @Update("""
            UPDATE control_a2a_outbox
            SET status = 'DEAD', lease_owner = NULL, lease_until = NULL,
                last_error_code = 'A2A_DISPATCH_OUTCOME_UNKNOWN',
                last_error_summary = 'Worker lease expired after dispatch may have started'
            WHERE id = #{id} AND status = 'CLAIMED' AND lease_until < #{now}
            """)
    int markExpiredClaimUnknown(@Param("id") long id, @Param("now") LocalDateTime now);
}
