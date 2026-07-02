package com.enterprise.ai.runtime.workflow;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class RuntimeAgentWorkflowBindingService {

    private final RuntimeAgentWorkflowBindingMapper mapper;

    public List<RuntimeAgentWorkflowBindingView> list(String agentId) {
        var query = Wrappers.<RuntimeAgentWorkflowBindingEntity>lambdaQuery()
                .orderByDesc(RuntimeAgentWorkflowBindingEntity::getPriority)
                .orderByDesc(RuntimeAgentWorkflowBindingEntity::getUpdatedAt);
        if (StringUtils.hasText(agentId)) {
            query.eq(RuntimeAgentWorkflowBindingEntity::getAgentId, agentId.trim());
        }
        return mapper.selectList(query).stream()
                .map(RuntimeAgentWorkflowBindingService::toView)
                .toList();
    }

    @Transactional
    public RuntimeAgentWorkflowBindingView create(String agentId, RuntimeAgentWorkflowBindingView request) {
        if (request == null) {
            throw new IllegalArgumentException("binding is required");
        }
        RuntimeAgentWorkflowBindingEntity entity = toEntity(request);
        if (StringUtils.hasText(agentId)) {
            entity.setAgentId(agentId.trim());
        }
        normalizeForCreate(entity);
        mapper.insert(entity);
        return toView(entity);
    }

    public Optional<RuntimeAgentWorkflowBindingView> findById(Long id) {
        if (id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapper.selectById(id))
                .map(RuntimeAgentWorkflowBindingService::toView);
    }

    @Transactional
    public Optional<RuntimeAgentWorkflowBindingView> update(String agentId,
                                                            Long bindingId,
                                                            RuntimeAgentWorkflowBindingView update) {
        RuntimeAgentWorkflowBindingEntity current = mapper.selectById(bindingId);
        if (current == null || !sameAgent(agentId, current)) {
            return Optional.empty();
        }
        if (update != null) {
            merge(current, update);
            current.setUpdatedAt(LocalDateTime.now());
            mapper.updateById(current);
        }
        return Optional.of(toView(current));
    }

    @Transactional
    public boolean delete(String agentId, Long bindingId) {
        RuntimeAgentWorkflowBindingEntity current = mapper.selectById(bindingId);
        return current != null && sameAgent(agentId, current) && mapper.deleteById(bindingId) > 0;
    }

    public Optional<RuntimeAgentWorkflowBindingView> resolvePreview(RuntimeAgentWorkflowBindingResolveRequest request) {
        if (request == null || !StringUtils.hasText(request.agentId())) {
            return Optional.empty();
        }
        var query = Wrappers.<RuntimeAgentWorkflowBindingEntity>lambdaQuery()
                .eq(RuntimeAgentWorkflowBindingEntity::getAgentId, request.agentId().trim())
                .eq(RuntimeAgentWorkflowBindingEntity::getEnabled, true);
        if (StringUtils.hasText(request.projectCode())) {
            query.and(wrapper -> wrapper
                    .eq(RuntimeAgentWorkflowBindingEntity::getProjectCode, request.projectCode().trim())
                    .or()
                    .isNull(RuntimeAgentWorkflowBindingEntity::getProjectCode));
        }
        return mapper.selectList(query).stream()
                .map(binding -> new RankedBinding(binding, rank(binding, request)))
                .filter(ranked -> ranked.rank() > 0)
                .max(Comparator.comparingInt(RankedBinding::rank)
                        .thenComparingInt(ranked -> priority(ranked.binding()))
                        .thenComparing(ranked -> updatedAtKey(ranked.binding()))
                        .thenComparingLong(ranked -> idKey(ranked.binding())))
                .map(RankedBinding::binding)
                .map(RuntimeAgentWorkflowBindingService::toView);
    }

    private void normalizeForCreate(RuntimeAgentWorkflowBindingEntity entity) {
        if (!StringUtils.hasText(entity.getAgentId())) {
            throw new IllegalArgumentException("agentId is required");
        }
        entity.setAgentId(entity.getAgentId().trim());
        if (!StringUtils.hasText(entity.getWorkflowId())) {
            throw new IllegalArgumentException("workflowId is required");
        }
        entity.setWorkflowId(entity.getWorkflowId().trim());
        if (StringUtils.hasText(entity.getProjectCode())) {
            entity.setProjectCode(entity.getProjectCode().trim());
        }
        if (!StringUtils.hasText(entity.getBindingType())) {
            entity.setBindingType("DEFAULT");
        } else {
            entity.setBindingType(entity.getBindingType().trim());
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
    }

    private void merge(RuntimeAgentWorkflowBindingEntity current, RuntimeAgentWorkflowBindingView update) {
        if (StringUtils.hasText(update.workflowId())) current.setWorkflowId(update.workflowId().trim());
        if (StringUtils.hasText(update.bindingType())) current.setBindingType(update.bindingType().trim());
        if (update.projectCode() != null) current.setProjectCode(update.projectCode());
        if (update.pageKey() != null) current.setPageKey(update.pageKey());
        if (update.routePattern() != null) current.setRoutePattern(update.routePattern());
        if (update.actionKey() != null) current.setActionKey(update.actionKey());
        if (update.intentType() != null) current.setIntentType(update.intentType());
        if (update.priority() != null) current.setPriority(update.priority());
        if (update.enabled() != null) current.setEnabled(update.enabled());
        if (update.guardConfigJson() != null) current.setGuardConfigJson(update.guardConfigJson());
        if (update.metadataJson() != null) current.setMetadataJson(update.metadataJson());
    }

    private boolean sameAgent(String agentId, RuntimeAgentWorkflowBindingEntity binding) {
        return StringUtils.hasText(agentId) && agentId.trim().equals(binding.getAgentId());
    }

    private static RuntimeAgentWorkflowBindingEntity toEntity(RuntimeAgentWorkflowBindingView view) {
        RuntimeAgentWorkflowBindingEntity entity = new RuntimeAgentWorkflowBindingEntity();
        entity.setId(view.id());
        entity.setAgentId(view.agentId());
        entity.setWorkflowId(view.workflowId());
        entity.setProjectCode(view.projectCode());
        entity.setBindingType(view.bindingType());
        entity.setPageKey(view.pageKey());
        entity.setRoutePattern(view.routePattern());
        entity.setActionKey(view.actionKey());
        entity.setIntentType(view.intentType());
        entity.setPriority(view.priority());
        entity.setEnabled(view.enabled());
        entity.setGuardConfigJson(view.guardConfigJson());
        entity.setMetadataJson(view.metadataJson());
        entity.setCreatedAt(view.createdAt());
        entity.setUpdatedAt(view.updatedAt());
        return entity;
    }

    private static RuntimeAgentWorkflowBindingView toView(RuntimeAgentWorkflowBindingEntity entity) {
        return new RuntimeAgentWorkflowBindingView(
                entity.getId(),
                entity.getAgentId(),
                entity.getWorkflowId(),
                entity.getProjectCode(),
                entity.getBindingType(),
                entity.getPageKey(),
                entity.getRoutePattern(),
                entity.getActionKey(),
                entity.getIntentType(),
                entity.getPriority(),
                entity.getEnabled(),
                entity.getGuardConfigJson(),
                entity.getMetadataJson(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    private int rank(RuntimeAgentWorkflowBindingEntity binding, RuntimeAgentWorkflowBindingResolveRequest request) {
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

    private int priority(RuntimeAgentWorkflowBindingEntity binding) {
        return binding.getPriority() == null ? 0 : binding.getPriority();
    }

    private String updatedAtKey(RuntimeAgentWorkflowBindingEntity binding) {
        return binding.getUpdatedAt() == null ? "" : binding.getUpdatedAt().toString();
    }

    private long idKey(RuntimeAgentWorkflowBindingEntity binding) {
        return binding.getId() == null ? 0L : binding.getId();
    }

    private record RankedBinding(RuntimeAgentWorkflowBindingEntity binding, int rank) {
    }
}
