package com.enterprise.ai.agent.workflow;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface WorkflowVersionMapper extends BaseMapper<WorkflowVersionEntity> {

    default List<WorkflowVersionEntity> listByWorkflow(String workflowId) {
        return selectList(Wrappers.<WorkflowVersionEntity>lambdaQuery()
                .eq(WorkflowVersionEntity::getWorkflowId, workflowId)
                .orderByDesc(WorkflowVersionEntity::getCreatedAt)
                .orderByDesc(WorkflowVersionEntity::getId));
    }

    default List<WorkflowVersionEntity> listActive(String workflowId) {
        return selectList(Wrappers.<WorkflowVersionEntity>lambdaQuery()
                .eq(WorkflowVersionEntity::getWorkflowId, workflowId)
                .eq(WorkflowVersionEntity::getStatus, "ACTIVE")
                .orderByDesc(WorkflowVersionEntity::getRolloutPercent)
                .orderByDesc(WorkflowVersionEntity::getId));
    }
}
