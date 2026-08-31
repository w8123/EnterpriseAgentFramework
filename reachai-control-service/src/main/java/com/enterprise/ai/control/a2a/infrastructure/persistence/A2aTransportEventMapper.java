package com.enterprise.ai.control.a2a.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

@Mapper
public interface A2aTransportEventMapper extends BaseMapper<A2aTransportEventEntity> {

    @Select("""
            SELECT COUNT(*) AS total_count,
                   COALESCE(SUM(CASE WHEN success = 1 THEN 1 ELSE 0 END), 0) AS success_count,
                   CAST(ROUND(AVG(latency_ms)) AS SIGNED) AS average_latency_ms
            FROM control_a2a_transport_event
            WHERE created_at >= #{since}
            """)
    A2aTransportMetricsRow aggregateSince(@Param("since") LocalDateTime since);
}
