package com.enterprise.ai.control.a2a.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface A2aOutboundExecutionMapper extends BaseMapper<A2aOutboundExecutionEntity> {

    @Select("""
            SELECT * FROM control_a2a_outbound_execution
            WHERE task_ref_id = #{taskRefId}
            LIMIT 1
            """)
    A2aOutboundExecutionEntity findByTaskRefId(@Param("taskRefId") long taskRefId);

    @Select("""
            SELECT * FROM control_a2a_outbound_execution
            WHERE task_ref_id = #{taskRefId}
            LIMIT 1
            FOR UPDATE
            """)
    A2aOutboundExecutionEntity lockByTaskRefId(@Param("taskRefId") long taskRefId);

    @Select("""
            SELECT id FROM control_a2a_outbound_execution
            WHERE poll_status = 'ACTIVE'
              AND next_poll_at IS NOT NULL
              AND next_poll_at <= #{now}
              AND (lease_until IS NULL OR lease_until < #{now})
            ORDER BY next_poll_at ASC, id ASC
            LIMIT #{limit}
            """)
    List<Long> findDueIds(@Param("now") LocalDateTime now, @Param("limit") int limit);

    @Update("""
            UPDATE control_a2a_outbound_execution
            SET lease_owner = #{workerId}, lease_until = #{leaseUntil}
            WHERE id = #{id}
              AND poll_status = 'ACTIVE'
              AND next_poll_at IS NOT NULL
              AND next_poll_at <= #{now}
              AND (lease_until IS NULL OR lease_until < #{now})
            """)
    int claim(@Param("id") long id,
              @Param("workerId") String workerId,
              @Param("now") LocalDateTime now,
              @Param("leaseUntil") LocalDateTime leaseUntil);

    @Update("""
            UPDATE control_a2a_outbound_execution
            SET lease_owner = #{workerId}, lease_until = #{leaseUntil}
            WHERE task_ref_id = #{taskRefId}
              AND poll_status <> 'TERMINAL'
              AND (lease_until IS NULL OR lease_until < #{now})
            """)
    int claimNow(@Param("taskRefId") long taskRefId,
                 @Param("workerId") String workerId,
                 @Param("now") LocalDateTime now,
                 @Param("leaseUntil") LocalDateTime leaseUntil);

    @Update("""
            UPDATE control_a2a_outbound_execution
            SET poll_status = 'ACTIVE', next_poll_at = #{nextPollAt},
                last_polled_at = #{lastPolledAt}, poll_attempt_count = #{pollAttemptCount},
                last_poll_error_code = #{errorCode}, last_poll_error_summary = #{errorSummary},
                lease_owner = NULL, lease_until = NULL
            WHERE task_ref_id = #{taskRefId}
              AND poll_status = 'ACTIVE'
              AND lease_owner = #{workerId}
            """)
    int schedule(@Param("taskRefId") long taskRefId,
                 @Param("workerId") String workerId,
                 @Param("nextPollAt") LocalDateTime nextPollAt,
                 @Param("lastPolledAt") LocalDateTime lastPolledAt,
                 @Param("pollAttemptCount") int pollAttemptCount,
                 @Param("errorCode") String errorCode,
                 @Param("errorSummary") String errorSummary);

    @Update("""
            UPDATE control_a2a_outbound_execution
            SET poll_status = 'ACTIVE', next_poll_at = #{nextPollAt},
                last_poll_error_code = NULL, last_poll_error_summary = NULL,
                lease_owner = NULL, lease_until = NULL
            WHERE task_ref_id = #{taskRefId}
              AND poll_status <> 'TERMINAL'
            """)
    int activate(@Param("taskRefId") long taskRefId,
                 @Param("nextPollAt") LocalDateTime nextPollAt);

    @Update("""
            UPDATE control_a2a_outbound_execution
            SET poll_status = 'PAUSED', next_poll_at = NULL,
                last_poll_error_code = #{reasonCode}, last_poll_error_summary = #{reasonSummary},
                lease_owner = NULL, lease_until = NULL
            WHERE task_ref_id = #{taskRefId}
              AND poll_status <> 'TERMINAL'
            """)
    int pause(@Param("taskRefId") long taskRefId,
              @Param("reasonCode") String reasonCode,
              @Param("reasonSummary") String reasonSummary);

    @Update("""
            UPDATE control_a2a_outbound_execution
            SET poll_status = 'TERMINAL', next_poll_at = NULL,
                lease_owner = NULL, lease_until = NULL
            WHERE task_ref_id = #{taskRefId}
            """)
    int markTerminal(@Param("taskRefId") long taskRefId);
}
