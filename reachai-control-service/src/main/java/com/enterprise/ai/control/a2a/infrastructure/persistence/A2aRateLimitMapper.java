package com.enterprise.ai.control.a2a.infrastructure.persistence;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

@Mapper
public interface A2aRateLimitMapper {

    @Insert("""
            INSERT INTO control_a2a_rate_limit_window
                (principal_id, publication_id, window_start, request_count)
            VALUES (#{principalId}, #{publicationId}, #{windowStart}, 1)
            ON DUPLICATE KEY UPDATE request_count = request_count + 1
            """)
    int increment(
            @Param("principalId") long principalId,
            @Param("publicationId") long publicationId,
            @Param("windowStart") LocalDateTime windowStart);

    @Select("""
            SELECT request_count FROM control_a2a_rate_limit_window
            WHERE principal_id = #{principalId}
              AND publication_id = #{publicationId}
              AND window_start = #{windowStart}
            """)
    Integer current(
            @Param("principalId") long principalId,
            @Param("publicationId") long publicationId,
            @Param("windowStart") LocalDateTime windowStart);

    @Delete("DELETE FROM control_a2a_rate_limit_window WHERE window_start < #{cutoff}")
    int deleteBefore(@Param("cutoff") LocalDateTime cutoff);
}
