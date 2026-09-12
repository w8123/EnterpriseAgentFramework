package com.enterprise.ai.runtime.automation;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface RuntimeAutomationExecutionSlotMapper {

    @Delete("""
            DELETE FROM runtime_automation_execution_slot
            WHERE automation_id = #{automationId} AND occurrence_id = #{occurrenceId}
              AND lease_token = #{leaseToken}
            """)
    int releaseLease(@Param("automationId") Long automationId,
                     @Param("occurrenceId") Long occurrenceId,
                     @Param("leaseToken") String leaseToken);

    // MySQL evaluates duplicate-key assignments left to right; preserve the old deadline until the last assignment.
    @Insert("""
            INSERT INTO runtime_automation_execution_slot
                (automation_id, slot_no, occurrence_id, lease_owner, lease_token, leased_until, updated_at)
            VALUES
                (#{automationId}, #{slotNo}, #{occurrenceId}, #{leaseOwner}, #{leaseToken}, #{leasedUntil}, UTC_TIMESTAMP(6))
            ON DUPLICATE KEY UPDATE
                occurrence_id = CASE WHEN leased_until < UTC_TIMESTAMP(6) THEN VALUES(occurrence_id) ELSE occurrence_id END,
                lease_owner = CASE WHEN leased_until < UTC_TIMESTAMP(6) THEN VALUES(lease_owner) ELSE lease_owner END,
                lease_token = CASE WHEN leased_until < UTC_TIMESTAMP(6) THEN VALUES(lease_token) ELSE lease_token END,
                updated_at = CASE WHEN leased_until < UTC_TIMESTAMP(6) THEN UTC_TIMESTAMP(6) ELSE updated_at END,
                leased_until = CASE WHEN leased_until < UTC_TIMESTAMP(6) THEN VALUES(leased_until) ELSE leased_until END
            """)
    int tryAcquire(@Param("automationId") Long automationId,
                   @Param("slotNo") int slotNo,
                   @Param("occurrenceId") Long occurrenceId,
                   @Param("leaseOwner") String leaseOwner,
                   @Param("leaseToken") String leaseToken,
                   @Param("leasedUntil") LocalDateTime leasedUntil);

    @Select("""
            SELECT COUNT(*) FROM runtime_automation_execution_slot
            WHERE automation_id = #{automationId} AND slot_no = #{slotNo}
              AND occurrence_id = #{occurrenceId} AND lease_token = #{leaseToken}
              AND leased_until >= UTC_TIMESTAMP(6)
            """)
    int owns(@Param("automationId") Long automationId,
             @Param("slotNo") int slotNo,
             @Param("occurrenceId") Long occurrenceId,
             @Param("leaseToken") String leaseToken);

    @Update("""
            UPDATE runtime_automation_execution_slot
            SET leased_until = #{leasedUntil}, updated_at = UTC_TIMESTAMP(6)
            WHERE automation_id = #{automationId} AND slot_no = #{slotNo}
              AND occurrence_id = #{occurrenceId} AND lease_token = #{leaseToken}
            """)
    int renew(@Param("automationId") Long automationId,
              @Param("slotNo") int slotNo,
              @Param("occurrenceId") Long occurrenceId,
              @Param("leaseToken") String leaseToken,
              @Param("leasedUntil") LocalDateTime leasedUntil);

    @Delete("""
            DELETE FROM runtime_automation_execution_slot
            WHERE automation_id = #{automationId} AND slot_no = #{slotNo}
              AND occurrence_id = #{occurrenceId} AND lease_token = #{leaseToken}
            """)
    int release(@Param("automationId") Long automationId,
                @Param("slotNo") int slotNo,
                @Param("occurrenceId") Long occurrenceId,
                @Param("leaseToken") String leaseToken);
}
