package com.enterprise.ai.runtime.workflow;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface RuntimeWorkflowVersionMapper extends BaseMapper<RuntimeWorkflowVersionEntity> {

    default List<RuntimeWorkflowVersionEntity> listByWorkflow(String workflowId) {
        return selectList(Wrappers.<RuntimeWorkflowVersionEntity>lambdaQuery()
                .eq(RuntimeWorkflowVersionEntity::getWorkflowId, workflowId)
                .orderByDesc(RuntimeWorkflowVersionEntity::getCreatedAt)
                .orderByDesc(RuntimeWorkflowVersionEntity::getId));
    }

    default List<RuntimeWorkflowVersionEntity> listActive(String workflowId) {
        return selectList(Wrappers.<RuntimeWorkflowVersionEntity>lambdaQuery()
                .eq(RuntimeWorkflowVersionEntity::getWorkflowId, workflowId)
                .eq(RuntimeWorkflowVersionEntity::getStatus, "ACTIVE")
                .orderByDesc(RuntimeWorkflowVersionEntity::getRolloutPercent)
                .orderByDesc(RuntimeWorkflowVersionEntity::getId));
    }
}
