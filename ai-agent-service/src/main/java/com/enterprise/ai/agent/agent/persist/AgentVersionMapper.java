package com.enterprise.ai.agent.agent.persist;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface AgentVersionMapper extends BaseMapper<AgentVersionEntity> {

    /** 查询某个 Agent 下所有版本（按 create_time 倒序）。 */
    default List<AgentVersionEntity> listByAgent(String agentId) {
        return selectList(Wrappers.<AgentVersionEntity>lambdaQuery()
                .eq(AgentVersionEntity::getAgentId, agentId)
                .orderByDesc(AgentVersionEntity::getCreateTime));
    }

    /** 查询某 Agent 的所有 ACTIVE 版本（灰度分流用）。 */
    default List<AgentVersionEntity> listActive(String agentId) {
        return selectList(Wrappers.<AgentVersionEntity>lambdaQuery()
                .eq(AgentVersionEntity::getAgentId, agentId)
                .eq(AgentVersionEntity::getStatus, "ACTIVE")
                .orderByDesc(AgentVersionEntity::getRolloutPercent));
    }
}
