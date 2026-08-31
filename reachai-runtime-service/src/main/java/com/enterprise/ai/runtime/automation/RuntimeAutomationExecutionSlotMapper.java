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

    @Insert("""
            INSERT INTO runtime_automation_execution_slot
                (automation_id, slot_no, occurrence_id, lease_owner, lease_token, leased_until, updated_at)
            VALUES
                (#{automationId}, #{slotNo}, #{occurrenceId}, #{leaseOwner}, #{leaseToken}, #{leasedUntil}, NOW(6))
            ON DUPLICATE KEY UPDATE
                occurrence_id = IF(leased_until < NOW(6), VALUES(occurrence_id), occurrence_id),
                lease_owner = IF(leased_until < NOW(6), VALUES(lease_owner), lease_owner),
                lease_token = IF(leased_until < NOW(6), VALUES(lease_token), lease_token),
                leased_until = IF(leased_until < NOW(6), VALUES(leased_until), leased_until),
                updated_at = IF(leased_until < NOW(6), NOW(6), updated_at)
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
              AND leased_until >= NOW(6)
            """)
    int owns(@Param("automationId") Long automationId,
             @Param("slotNo") int slotNo,
             @Param("occurrenceId") Long occurrenceId,
             @Param("leaseToken") String leaseToken);

    @Update("""
            UPDATE runtime_automation_execution_slot
            SET leased_until = #{leasedUntil}, updated_at = NOW(6)
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
