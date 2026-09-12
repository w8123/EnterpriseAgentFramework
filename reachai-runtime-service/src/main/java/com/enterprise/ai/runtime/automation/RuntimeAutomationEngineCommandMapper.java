package com.enterprise.ai.runtime.automation;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface RuntimeAutomationEngineCommandMapper extends BaseMapper<RuntimeAutomationEngineCommandEntity> {

    @Select("""
            SELECT id FROM runtime_automation_engine_command
            WHERE (status IN ('PENDING', 'RETRY') AND available_at <= UTC_TIMESTAMP(6))
               OR (status = 'PROCESSING' AND leased_until < UTC_TIMESTAMP(6))
            ORDER BY id ASC LIMIT 1
            """)
    Long findCandidateId();

    @Update("""
            UPDATE runtime_automation_engine_command
            SET status = 'PROCESSING', lease_owner = #{leaseOwner}, lease_token = #{leaseToken},
                leased_until = #{leasedUntil}, attempt_count = attempt_count + 1,
                updated_at = UTC_TIMESTAMP(6)
            WHERE id = #{id} AND (
                (status IN ('PENDING', 'RETRY') AND available_at <= UTC_TIMESTAMP(6))
                OR (status = 'PROCESSING' AND leased_until < UTC_TIMESTAMP(6))
            )
            """)
    int claim(@Param("id") Long id,
              @Param("leaseOwner") String leaseOwner,
              @Param("leaseToken") String leaseToken,
              @Param("leasedUntil") LocalDateTime leasedUntil);

    @Update("""
            UPDATE runtime_automation_engine_command
            SET status = 'COMPLETED', lease_owner = NULL, lease_token = NULL, leased_until = NULL,
                completed_at = UTC_TIMESTAMP(6), updated_at = UTC_TIMESTAMP(6)
            WHERE id = #{id} AND status = 'PROCESSING' AND lease_token = #{leaseToken}
            """)
    int complete(@Param("id") Long id, @Param("leaseToken") String leaseToken);

    @Update("""
            UPDATE runtime_automation_engine_command
            SET status = #{nextStatus}, available_at = #{availableAt},
                lease_owner = NULL, lease_token = NULL, leased_until = NULL,
                last_error_code = #{errorCode}, last_error_message = #{errorMessage},
                completed_at = CASE WHEN #{nextStatus} = 'DEAD' THEN UTC_TIMESTAMP(6) ELSE NULL END,
                updated_at = UTC_TIMESTAMP(6)
            WHERE id = #{id} AND status = 'PROCESSING' AND lease_token = #{leaseToken}
            """)
    int release(@Param("id") Long id,
                @Param("leaseToken") String leaseToken,
                @Param("nextStatus") String nextStatus,
                @Param("availableAt") LocalDateTime availableAt,
                @Param("errorCode") String errorCode,
                @Param("errorMessage") String errorMessage);
}
