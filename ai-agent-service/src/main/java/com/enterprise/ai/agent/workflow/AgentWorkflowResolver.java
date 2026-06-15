package com.enterprise.ai.agent.workflow;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AgentWorkflowResolver {

    private final AgentWorkflowBindingMapper mapper;

    public Optional<AgentWorkflowBindingEntity> resolve(AgentWorkflowResolveRequest request) {
        if (request == null || !StringUtils.hasText(request.agentId())) {
            return Optional.empty();
        }
        var query = Wrappers.<AgentWorkflowBindingEntity>lambdaQuery()
                .eq(AgentWorkflowBindingEntity::getAgentId, request.agentId().trim())
                .eq(AgentWorkflowBindingEntity::getEnabled, true);
        if (StringUtils.hasText(request.projectCode())) {
            query.and(wrapper -> wrapper
                    .eq(AgentWorkflowBindingEntity::getProjectCode, request.projectCode().trim())
                    .or()
                    .isNull(AgentWorkflowBindingEntity::getProjectCode));
        }
        List<AgentWorkflowBindingEntity> candidates = mapper.selectList(query);
        return candidates.stream()
                .map(binding -> new RankedBinding(binding, rank(binding, request)))
                .filter(ranked -> ranked.rank() > 0)
                .max(Comparator.comparingInt(RankedBinding::rank)
                        .thenComparingInt(ranked -> priority(ranked.binding())))
                .map(RankedBinding::binding);
    }

    private int rank(AgentWorkflowBindingEntity binding, AgentWorkflowResolveRequest request) {
        String type = normalize(binding.getBindingType());
        boolean page = equalsText(binding.getPageKey(), request.pageKey());
        boolean action = equalsText(binding.getActionKey(), request.actionKey());
        boolean intent = equalsText(binding.getIntentType(), request.intentType());
        boolean route = routeMatches(binding.getRoutePattern(), request.route());
        if (page && action) return 60;
        if (page && intent) return 50;
        if (page && ("PAGE".equals(type) || !StringUtils.hasText(binding.getActionKey()))) return 40;
        if (route) return 30;
        if (intent) return 20;
        if ("DEFAULT".equals(type)) return 10;
        return 0;
    }

    private boolean routeMatches(String routePattern, String route) {
        if (!StringUtils.hasText(routePattern) || !StringUtils.hasText(route)) {
            return false;
        }
        String pattern = routePattern.trim();
        String value = route.trim();
        if (pattern.endsWith("*")) {
            return value.startsWith(pattern.substring(0, pattern.length() - 1));
        }
        return pattern.equals(value);
    }

    private boolean equalsText(String left, String right) {
        return StringUtils.hasText(left) && StringUtils.hasText(right) && left.trim().equals(right.trim());
    }

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim().toUpperCase() : "";
    }

    private int priority(AgentWorkflowBindingEntity binding) {
        return binding.getPriority() == null ? 0 : binding.getPriority();
    }

    private record RankedBinding(AgentWorkflowBindingEntity binding, int rank) {
    }
}
