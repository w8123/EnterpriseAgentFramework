package com.enterprise.ai.control.aicoding.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

@Mapper
public interface AiCodingTaskEventMapper extends BaseMapper<AiCodingTaskEventEntity> {

    @Insert("""
            INSERT IGNORE INTO control_ai_coding_task_event
                (task_id, client_event_id, event_type, execution_status_after,
                 message, payload_json, actor_type, actor_name, created_at)
            VALUES
                (#{taskId}, #{clientEventId}, #{eventType}, #{executionStatusAfter},
                 #{message}, #{payloadJson}, 'REACHAI', 'managed-executor', #{createdAt})
            """)
    int insertManagedEvent(@Param("taskId") String taskId,
                           @Param("clientEventId") String clientEventId,
                           @Param("eventType") String eventType,
                           @Param("executionStatusAfter") String executionStatusAfter,
                           @Param("message") String message,
                           @Param("payloadJson") String payloadJson,
                           @Param("createdAt") LocalDateTime createdAt);
}
