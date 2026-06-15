package com.enterprise.ai.agent.workflow;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AgentWorkflowBindingService {

    private final AgentWorkflowBindingMapper mapper;

    public List<AgentWorkflowBindingEntity> list(String agentId) {
        var query = Wrappers.<AgentWorkflowBindingEntity>lambdaQuery()
                .orderByDesc(AgentWorkflowBindingEntity::getPriority)
                .orderByDesc(AgentWorkflowBindingEntity::getUpdatedAt);
        if (StringUtils.hasText(agentId)) {
            query.eq(AgentWorkflowBindingEntity::getAgentId, agentId.trim());
        }
        return mapper.selectList(query);
    }

    @Transactional
    public AgentWorkflowBindingEntity create(String agentId, AgentWorkflowBindingEntity entity) {
        if (entity == null) {
            throw new IllegalArgumentException("binding is required");
        }
        if (StringUtils.hasText(agentId)) {
            entity.setAgentId(agentId.trim());
        }
        if (!StringUtils.hasText(entity.getAgentId())) {
            throw new IllegalArgumentException("agentId is required");
        }
        if (!StringUtils.hasText(entity.getWorkflowId())) {
            throw new IllegalArgumentException("workflowId is required");
        }
        if (!StringUtils.hasText(entity.getBindingType())) {
            entity.setBindingType("DEFAULT");
        }
        if (entity.getPriority() == null) {
            entity.setPriority(0);
        }
        if (entity.getEnabled() == null) {
            entity.setEnabled(true);
        }
        LocalDateTime now = LocalDateTime.now();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        mapper.insert(entity);
        return entity;
    }
}
