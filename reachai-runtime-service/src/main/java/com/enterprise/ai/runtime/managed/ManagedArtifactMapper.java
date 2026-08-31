package com.enterprise.ai.runtime.managed;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface ManagedArtifactMapper extends BaseMapper<ManagedArtifactEntity> {

    @Update("""
            UPDATE runtime_managed_artifact
            SET validation_status = #{validationStatus},
                scan_status = #{scanStatus},
                rejection_code = #{rejectionCode},
                updated_at = #{now}
            WHERE execution_id = #{executionId}
              AND artifact_id = #{artifactId}
              AND sha256 = #{sha256}
            """)
    int confirm(@Param("executionId") String executionId,
                @Param("artifactId") String artifactId,
                @Param("sha256") String sha256,
                @Param("validationStatus") String validationStatus,
                @Param("scanStatus") String scanStatus,
                @Param("rejectionCode") String rejectionCode,
                @Param("now") LocalDateTime now);
}
