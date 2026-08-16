package com.enterprise.ai.control.context;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface ContextMemoryOutboxMapper extends BaseMapper<ContextMemoryOutboxEntity> {

    @Update("""
            UPDATE control_context_memory_outbox
               SET payload_json = #{payloadJson},
                   status = CASE
                       WHEN status IN ('PENDING', 'PUBLISHING', 'DEAD') THEN 'SUPERSEDED'
                       ELSE status
                   END,
                   locked_at = NULL,
                   last_error = NULL,
                   updated_at = #{now}
             WHERE aggregate_type = 'PERSONAL_MEMORY'
               AND aggregate_id = #{aggregateId}
            """)
    int scrubAndSupersedePersonalMemory(@Param("aggregateId") String aggregateId,
                                        @Param("payloadJson") String payloadJson,
                                        @Param("now") LocalDateTime now);

    @Select("""
            SELECT COALESCE(SUM(CASE WHEN status IN ('PENDING', 'PUBLISHING') THEN 1 ELSE 0 END), 0)
                       AS backlog_count,
                   COALESCE(SUM(CASE WHEN status = 'DEAD' THEN 1 ELSE 0 END), 0)
                       AS dead_count,
                   COALESCE(MAX(CASE
                       WHEN status IN ('PENDING', 'PUBLISHING')
                       THEN GREATEST(TIMESTAMPDIFF(SECOND, created_at, #{now}), 0)
                       ELSE 0 END), 0) AS oldest_unpublished_seconds
              FROM control_context_memory_outbox
             WHERE aggregate_type = 'PERSONAL_MEMORY'
               AND status IN ('PENDING', 'PUBLISHING', 'DEAD')
            """)
    PersonalMemoryOutboxStatusRow selectPersonalMemoryStatus(@Param("now") LocalDateTime now);

    @Select("""
            SELECT COUNT(*) AS total_count,
                   COALESCE(SUM(CASE WHEN status = 'PUBLISHED' THEN 1 ELSE 0 END), 0)
                       AS published_count,
                   COALESCE(SUM(CASE WHEN status = 'SUPERSEDED' THEN 1 ELSE 0 END), 0)
                       AS superseded_count,
                   COALESCE(SUM(CASE WHEN status IN ('PENDING', 'PUBLISHING') THEN 1 ELSE 0 END), 0)
                       AS pending_count,
                   COALESCE(SUM(CASE WHEN status = 'DEAD' THEN 1 ELSE 0 END), 0)
                       AS dead_count,
                   COALESCE(SUM(CASE WHEN status NOT IN
                       ('PENDING', 'PUBLISHING', 'PUBLISHED', 'DEAD', 'SUPERSEDED')
                       THEN 1 ELSE 0 END), 0) AS other_count,
                   MAX(id) AS max_source_version
              FROM control_context_memory_outbox
             WHERE aggregate_type = 'PERSONAL_MEMORY'
               AND event_type = 'PERSONAL_MEMORY_DELETE'
               AND correlation_id = #{correlationId}
            """)
    PersonalMemoryErasureOutboxStatusRow selectErasureDeliveryStatus(
            @Param("correlationId") String correlationId);
}
