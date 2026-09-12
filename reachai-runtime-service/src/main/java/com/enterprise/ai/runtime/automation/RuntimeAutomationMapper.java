package com.enterprise.ai.runtime.automation;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface RuntimeAutomationMapper extends BaseMapper<RuntimeAutomationEntity> {

    @Update("""
            UPDATE runtime_automation
            SET name = #{name}, description = #{description}, project_id = #{projectId},
                project_code = #{projectCode}, current_version_id = #{versionId},
                status = #{status}, next_fire_at = #{nextFireAt}, revision = revision + 1,
                updated_by = #{actor}, updated_at = UTC_TIMESTAMP(6)
            WHERE id = #{id} AND revision = #{expectedRevision} AND status <> 'ARCHIVED'
            """)
    int updateVersion(@Param("id") Long id,
                      @Param("expectedRevision") Long expectedRevision,
                      @Param("name") String name,
                      @Param("description") String description,
                      @Param("projectId") Long projectId,
                      @Param("projectCode") String projectCode,
                      @Param("versionId") Long versionId,
                      @Param("status") String status,
                      @Param("nextFireAt") LocalDateTime nextFireAt,
                      @Param("actor") String actor);

    @Update("""
            UPDATE runtime_automation
            SET status = #{nextStatus}, next_fire_at = #{nextFireAt}, revision = revision + 1,
                updated_by = #{actor}, updated_at = UTC_TIMESTAMP(6)
            WHERE id = #{id} AND revision = #{expectedRevision} AND status <> 'ARCHIVED'
            """)
    int transition(@Param("id") Long id,
                   @Param("expectedRevision") Long expectedRevision,
                   @Param("nextStatus") String nextStatus,
                   @Param("nextFireAt") LocalDateTime nextFireAt,
                   @Param("actor") String actor);

    @Update("""
            UPDATE runtime_automation
            SET next_fire_at = #{nextFireAt}, last_fire_at = #{lastFireAt}, updated_at = UTC_TIMESTAMP(6)
            WHERE id = #{id}
            """)
    int updateFireTimes(@Param("id") Long id,
                        @Param("nextFireAt") LocalDateTime nextFireAt,
                        @Param("lastFireAt") LocalDateTime lastFireAt);

    @Update("""
            UPDATE runtime_automation
            SET status = 'COMPLETED', next_fire_at = NULL, last_fire_at = #{lastFireAt},
                revision = revision + 1, updated_by = 'AUTOMATION_ENGINE', updated_at = UTC_TIMESTAMP(6)
            WHERE id = #{id} AND current_version_id = #{versionId} AND status = 'ACTIVE'
            """)
    int completeOnce(@Param("id") Long id,
                     @Param("versionId") Long versionId,
                     @Param("lastFireAt") LocalDateTime lastFireAt);
}
