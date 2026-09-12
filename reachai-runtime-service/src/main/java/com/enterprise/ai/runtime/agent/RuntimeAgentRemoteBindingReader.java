package com.enterprise.ai.runtime.agent;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import java.util.List;

@Component
@RequiredArgsConstructor
public class RuntimeAgentRemoteBindingReader implements RuntimeAgentRemoteBindingQuery {
    private final RuntimeAgentRemoteBindingMapper bindings;
    private final RuntimeAgentConfigVersionMapper configurations;

    @Override
    @Transactional(readOnly = true)
    public RuntimeAgentRemoteBindingView requireActiveBinding(String agentId, long configVersionId, long bindingId) {
        RuntimeAgentConfigVersionEntity config = configurations.selectById(configVersionId);
        if (config == null || !java.util.Objects.equals(agentId, config.getAgentId())
                || !"ACTIVE".equalsIgnoreCase(config.getStatus())) {
            throw new IllegalArgumentException(
                    "outbound A2A delegation requires the active fixed Agent config version");
        }
        RuntimeAgentRemoteBindingEntity binding = bindings.selectOne(
                Wrappers.<RuntimeAgentRemoteBindingEntity>lambdaQuery()
                        .eq(RuntimeAgentRemoteBindingEntity::getId, bindingId)
                        .eq(RuntimeAgentRemoteBindingEntity::getAgentId, agentId)
                        .eq(RuntimeAgentRemoteBindingEntity::getAgentConfigVersionId, configVersionId)
                        .eq(RuntimeAgentRemoteBindingEntity::getEnabled, true)
                        .last("LIMIT 1"));
        if (binding == null) {
            throw new IllegalArgumentException(
                    "outbound A2A binding is not enabled in the active Agent config version");
        }
        return RuntimeAgentRemoteBindingView.fromEntity(binding);
    }

    @Override
    public List<RuntimeAgentRemoteBindingView> enabledBindings(String agentId, Long configVersionId) {
        if (!StringUtils.hasText(agentId) || configVersionId == null) return List.of();
        return bindings.selectList(Wrappers.<RuntimeAgentRemoteBindingEntity>lambdaQuery()
                        .eq(RuntimeAgentRemoteBindingEntity::getAgentId, agentId)
                        .eq(RuntimeAgentRemoteBindingEntity::getAgentConfigVersionId, configVersionId)
                        .eq(RuntimeAgentRemoteBindingEntity::getEnabled, true)
                        .orderByAsc(RuntimeAgentRemoteBindingEntity::getPriority)
                        .orderByAsc(RuntimeAgentRemoteBindingEntity::getId)).stream()
                .map(RuntimeAgentRemoteBindingView::fromEntity).toList();
    }
}
