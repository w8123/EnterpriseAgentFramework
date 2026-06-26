package com.enterprise.ai.agent.platform.control.context;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ContextBindingService {

    private final ContextBindingMapper bindingMapper;
    private final ContextAccessPolicyService accessPolicyService;

    @Transactional
    public ContextBindingResponse bindItemOnCreate(ContextItemEntity item,
                                                     ContextNamespaceEntity namespace,
                                                     ContextBindingRequest request,
                                                     ContextQueryRequest scope) {
        accessPolicyService.validateInitialBinding(item, namespace, request, scope);
        ContextBindingEntity entity = new ContextBindingEntity();
        entity.setItemId(item.getId());
        entity.setBindType(request.getBindType().trim().toUpperCase());
        entity.setBindId(request.getBindId().trim());
        entity.setBindKey(request.getBindKey());
        entity.setTenantId(request.getTenantId());
        entity.setProjectId(request.getProjectId());
        entity.setProjectCode(request.getProjectCode());
        entity.setStatus(ContextStatus.ACTIVE.name());
        entity.setCreatedAt(LocalDateTime.now());
        bindingMapper.insert(entity);
        return ContextViewMapper.toBindingResponse(entity);
    }

    @Transactional
    public ContextBindingResponse bindItem(Long itemId, ContextBindingRequest request, ContextQueryRequest scope) {
        accessPolicyService.requireItemWriteAccess(itemId, scope);
        validateBindingRequest(request);
        ContextBindingEntity entity = new ContextBindingEntity();
        entity.setItemId(itemId);
        entity.setBindType(request.getBindType().trim().toUpperCase());
        entity.setBindId(request.getBindId().trim());
        entity.setBindKey(request.getBindKey());
        entity.setTenantId(request.getTenantId());
        entity.setProjectId(request.getProjectId());
        entity.setProjectCode(request.getProjectCode());
        entity.setStatus(ContextStatus.ACTIVE.name());
        entity.setCreatedAt(LocalDateTime.now());
        bindingMapper.insert(entity);
        return ContextViewMapper.toBindingResponse(entity);
    }

    @Transactional
    public void unbindItem(Long bindingId, ContextQueryRequest scope) {
        ContextBindingEntity entity = bindingMapper.selectById(bindingId);
        if (entity == null || !ContextStatus.ACTIVE.name().equals(entity.getStatus())) {
            throw new IllegalArgumentException("Context binding not found: " + bindingId);
        }
        accessPolicyService.requireItemWriteAccess(entity.getItemId(), scope);
        entity.setStatus(ContextStatus.DELETED.name());
        entity.setDeletedAt(LocalDateTime.now());
        bindingMapper.updateById(entity);
    }

    public List<ContextBindingResponse> listBindingsForItem(Long itemId, ContextQueryRequest scope) {
        accessPolicyService.requireItemReadAccess(itemId, scope);
        return bindingMapper.selectList(
                        Wrappers.lambdaQuery(ContextBindingEntity.class)
                                .eq(ContextBindingEntity::getItemId, itemId)
                                .eq(ContextBindingEntity::getStatus, ContextStatus.ACTIVE.name())
                                .orderByDesc(ContextBindingEntity::getCreatedAt))
                .stream()
                .map(ContextViewMapper::toBindingResponse)
                .collect(Collectors.toList());
    }

    public Set<Long> findItemIdsByTarget(ContextBindType bindType, String bindId) {
        if (!StringUtils.hasText(bindId)) {
            return Collections.emptySet();
        }
        return bindingMapper.selectList(
                        Wrappers.lambdaQuery(ContextBindingEntity.class)
                                .eq(ContextBindingEntity::getBindType, bindType.name())
                                .eq(ContextBindingEntity::getBindId, bindId.trim())
                                .eq(ContextBindingEntity::getStatus, ContextStatus.ACTIVE.name()))
                .stream()
                .map(ContextBindingEntity::getItemId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    Set<Long> resolveBindingMatches(ContextQueryRequest query) {
        List<ContextBindType> targets = new ArrayList<>();
        if (StringUtils.hasText(query.getUserId())) {
            targets.add(ContextBindType.USER);
        }
        if (StringUtils.hasText(query.getAgentId())) {
            targets.add(ContextBindType.AGENT);
        }
        if (StringUtils.hasText(query.getWorkflowId())) {
            targets.add(ContextBindType.WORKFLOW);
        }
        if (StringUtils.hasText(query.getPageInstanceId())) {
            targets.add(ContextBindType.PAGE);
        }
        if (StringUtils.hasText(query.getSessionId())) {
            targets.add(ContextBindType.SESSION);
        }
        if (targets.isEmpty()) {
            return Collections.emptySet();
        }

        Set<Long> matched = new LinkedHashSet<>();
        for (ContextBindType bindType : targets) {
            String bindId = bindIdForType(query, bindType);
            if (StringUtils.hasText(bindId)) {
                matched.addAll(findItemIdsByTarget(bindType, bindId));
            }
        }
        return matched;
    }

    private String bindIdForType(ContextQueryRequest query, ContextBindType bindType) {
        return switch (bindType) {
            case USER -> query.getUserId();
            case AGENT -> query.getAgentId();
            case WORKFLOW -> query.getWorkflowId();
            case PAGE -> query.getPageInstanceId();
            case SESSION -> query.getSessionId();
            default -> null;
        };
    }

    private void validateBindingRequest(ContextBindingRequest request) {
        if (request == null || !StringUtils.hasText(request.getBindType()) || !StringUtils.hasText(request.getBindId())) {
            throw new IllegalArgumentException("bindType and bindId are required");
        }
        ContextBindType.valueOf(request.getBindType().trim().toUpperCase());
    }
}
