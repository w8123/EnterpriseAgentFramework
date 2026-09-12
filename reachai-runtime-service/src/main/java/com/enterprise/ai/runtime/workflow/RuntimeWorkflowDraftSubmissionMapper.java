package com.enterprise.ai.runtime.workflow;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface RuntimeWorkflowDraftSubmissionMapper extends BaseMapper<RuntimeWorkflowDraftSubmissionEntity> {
    @Select("""
            SELECT * FROM runtime_workflow_draft_submission
            WHERE workflow_id = #{workflowId} AND request_hash = #{requestHash}
            FOR UPDATE
            """)
    RuntimeWorkflowDraftSubmissionEntity findForUpdate(@Param("workflowId") String workflowId,
                                                       @Param("requestHash") String requestHash);

    @Select("""
            SELECT * FROM runtime_workflow_draft_submission
            WHERE workflow_id = #{workflowId} AND workflow_revision IS NOT NULL
            ORDER BY workflow_revision DESC, id DESC LIMIT 1
            """)
    RuntimeWorkflowDraftSubmissionEntity latestApplied(@Param("workflowId") String workflowId);

    @Update("""
            UPDATE runtime_workflow_draft_submission SET workflow_revision = #{revision}
            WHERE id = #{id} AND workflow_revision IS NULL
            """)
    int complete(@Param("id") Long id, @Param("revision") LocalDateTime revision);
}
