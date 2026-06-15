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

    public List<AgentWorkflowBindingEntity> listEnabledByIntentType(String intentType) {
        if (!StringUtils.hasText(intentType)) {
            return List.of();
        }
        return mapper.selectList(Wrappers.<AgentWorkflowBindingEntity>lambdaQuery()
                .eq(AgentWorkflowBindingEntity::getEnabled, true)
                .eq(AgentWorkflowBindingEntity::getIntentType, intentType.trim())
                .orderByDesc(AgentWorkflowBindingEntity::getPriority)
                .orderByDesc(AgentWorkflowBindingEntity::getUpdatedAt));
    }

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

    public AgentWorkflowBindingEntity findById(Long id) {
        if (id == null) {
            return null;
        }
        return mapper.selectById(id);
    }

    @Transactional
    public AgentWorkflowBindingEntity update(Long bindingId, AgentWorkflowBindingEntity update) {
        AgentWorkflowBindingEntity current = findById(bindingId);
        if (current == null) {
            throw new IllegalArgumentException("binding not found: " + bindingId);
        }
        if (update == null) {
            return current;
        }
        if (StringUtils.hasText(update.getWorkflowId())) {
            current.setWorkflowId(update.getWorkflowId().trim());
        }
        if (StringUtils.hasText(update.getBindingType())) {
            current.setBindingType(update.getBindingType().trim());
        }
        if (update.getProjectCode() != null) {
            current.setProjectCode(update.getProjectCode());
        }
        if (update.getPageKey() != null) {
            current.setPageKey(update.getPageKey());
        }
        if (update.getRoutePattern() != null) {
            current.setRoutePattern(update.getRoutePattern());
        }
        if (update.getActionKey() != null) {
            current.setActionKey(update.getActionKey());
        }
        if (update.getIntentType() != null) {
            current.setIntentType(update.getIntentType());
        }
        if (update.getPriority() != null) {
            current.setPriority(update.getPriority());
        }
        if (update.getEnabled() != null) {
            current.setEnabled(update.getEnabled());
        }
        if (update.getGuardConfigJson() != null) {
            current.setGuardConfigJson(update.getGuardConfigJson());
        }
        if (update.getMetadataJson() != null) {
            current.setMetadataJson(update.getMetadataJson());
        }
        current.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(current);
        return current;
    }

    @Transactional
    public boolean delete(Long bindingId) {
        return bindingId != null && mapper.deleteById(bindingId) > 0;
    }
}
