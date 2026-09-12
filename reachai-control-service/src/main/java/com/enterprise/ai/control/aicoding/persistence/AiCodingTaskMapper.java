package com.enterprise.ai.control.aicoding.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AiCodingTaskMapper extends BaseMapper<AiCodingTaskEntity> {

    @Select("""
            <script>
            SELECT task.task_id FROM control_ai_coding_task_target target
            JOIN control_ai_coding_task task ON task.task_id=target.task_id
            WHERE target.target_type=#{targetType} AND target.target_key=#{targetKey}
              AND target.target_role='PRIMARY' AND task.task_kind=#{taskKind}
              AND UPPER(task.executor_provider)=#{executorProvider}
              AND task.execution_status IN
                <foreach collection="statuses" item="status" open="(" separator="," close=")">#{status}</foreach>
            ORDER BY target.created_at DESC,target.id DESC LIMIT 1
            </script>
            """)
    String findLatestReusableTaskId(@Param("taskKind") String taskKind,
                                    @Param("executorProvider") String executorProvider,
                                    @Param("targetType") String targetType,
                                    @Param("targetKey") String targetKey,
                                    @Param("statuses") List<String> statuses);

    @Update("""
            UPDATE control_ai_coding_task
            SET managed_execution_id = #{executionId},
                managed_execution_status = #{managedStatus},
                managed_pending_interaction_id = #{pendingInteractionId},
                execution_status = 'RUNNING',
                started_at = COALESCE(started_at, #{now}),
                last_message = #{message},
                lock_version = lock_version + 1,
                updated_at = #{now}
            WHERE task_id = #{taskId}
              AND execution_mode = 'MANAGED_SANDBOX'
              AND (managed_execution_id IS NULL OR managed_execution_id = #{executionId})
              AND execution_status IN ('READY', 'RUNNING', 'WAITING_USER')
            """)
    int bindManagedExecution(@Param("taskId") String taskId,
                             @Param("executionId") String executionId,
                             @Param("managedStatus") String managedStatus,
                             @Param("pendingInteractionId") String pendingInteractionId,
                             @Param("message") String message,
                             @Param("now") LocalDateTime now);

    @Update("""
            UPDATE control_ai_coding_task
            SET managed_execution_status = #{managedStatus},
                managed_pending_interaction_id = #{pendingInteractionId},
                execution_status = #{taskStatus},
                started_at = CASE WHEN #{taskStatus} IN ('RUNNING', 'WAITING_USER')
                                  THEN COALESCE(started_at, #{now}) ELSE started_at END,
                result_submitted_at = CASE WHEN #{taskStatus} = 'RESULT_SUBMITTED'
                                           THEN COALESCE(result_submitted_at, #{now})
                                           ELSE result_submitted_at END,
                completed_at = CASE WHEN #{taskStatus} IN ('FAILED', 'CANCELLED')
                                    THEN COALESCE(completed_at, #{now}) ELSE completed_at END,
                last_message = #{message},
                lock_version = lock_version + 1,
                updated_at = #{now}
            WHERE task_id = #{taskId}
              AND execution_mode = 'MANAGED_SANDBOX'
              AND managed_execution_id = #{executionId}
              AND lock_version = #{expectedLockVersion}
            """)
    int projectManagedExecution(@Param("taskId") String taskId,
                                @Param("executionId") String executionId,
                                @Param("managedStatus") String managedStatus,
                                @Param("pendingInteractionId") String pendingInteractionId,
                                @Param("taskStatus") String taskStatus,
                                @Param("message") String message,
                                @Param("expectedLockVersion") Long expectedLockVersion,
                                @Param("now") LocalDateTime now);
}
