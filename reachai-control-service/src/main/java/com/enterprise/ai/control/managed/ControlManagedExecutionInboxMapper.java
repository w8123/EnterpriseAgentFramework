package com.enterprise.ai.control.managed;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface ControlManagedExecutionInboxMapper
        extends BaseMapper<ControlManagedExecutionInboxEntity> {

    @Select("""
            SELECT event_id
            FROM control_managed_execution_inbox
            WHERE projection_available_at <= #{now}
              AND (
                    projection_status IN ('RECEIVED', 'PENDING')
                    OR projection_status = 'PROCESSING'
                  )
            ORDER BY received_at ASC
            LIMIT #{limit}
            """)
    List<String> findProjectionCandidates(@Param("now") LocalDateTime now,
                                          @Param("limit") int limit);

    @Update("""
            UPDATE control_managed_execution_inbox
            SET projection_status = 'PROCESSING',
                projection_attempt_count = projection_attempt_count + 1,
                projection_available_at = #{leaseExpiresAt},
                projection_error = NULL
            WHERE event_id = #{eventId}
              AND projection_available_at <= #{now}
              AND projection_status IN ('RECEIVED', 'PENDING', 'PROCESSING')
            """)
    int claimProjection(@Param("eventId") String eventId,
                        @Param("now") LocalDateTime now,
                        @Param("leaseExpiresAt") LocalDateTime leaseExpiresAt);

    @Update("""
            UPDATE control_managed_execution_inbox
            SET projection_status = #{status},
                projection_error = #{error},
                projection_available_at = #{availableAt},
                applied_at = #{appliedAt}
            WHERE event_id = #{eventId}
              AND projection_status = 'PROCESSING'
            """)
    int finishProjection(@Param("eventId") String eventId,
                         @Param("status") String status,
                         @Param("error") String error,
                         @Param("availableAt") LocalDateTime availableAt,
                         @Param("appliedAt") LocalDateTime appliedAt);
}
