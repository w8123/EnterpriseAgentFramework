package com.enterprise.ai.agent.platform.control.context;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ContextNamespaceService {

    private final ContextNamespaceMapper namespaceMapper;
    private final ContextAccessPolicyService accessPolicyService;

    @Transactional
    public ContextNamespaceResponse createOrGetNamespace(ContextNamespaceRequest request) {
        if (!StringUtils.hasText(request.getNamespaceType())) {
            throw new IllegalArgumentException("namespaceType is required");
        }
        if (!StringUtils.hasText(request.getTenantId())) {
            throw new IllegalArgumentException("tenantId is required");
        }
        accessPolicyService.assertProjectAccessOnly(request.getProjectCode(), request.getProjectId());

        String namespaceKey = ContextKeyFactory.buildNamespaceKey(request);
        ContextNamespaceEntity existing = namespaceMapper.selectOne(
                Wrappers.lambdaQuery(ContextNamespaceEntity.class)
                        .eq(ContextNamespaceEntity::getNamespaceKey, namespaceKey)
                        .ne(ContextNamespaceEntity::getStatus, ContextStatus.DELETED.name())
                        .last("LIMIT 1"));
        if (existing != null) {
            return ContextViewMapper.toNamespaceResponse(existing);
        }

        ContextNamespaceEntity entity = new ContextNamespaceEntity();
        entity.setNamespaceKey(namespaceKey);
        entity.setNamespaceType(request.getNamespaceType().trim().toUpperCase());
        entity.setTenantId(request.getTenantId());
        entity.setProjectId(request.getProjectId());
        entity.setProjectCode(request.getProjectCode());
        entity.setOwnerType(request.getOwnerType());
        entity.setOwnerId(request.getOwnerId());
        entity.setDisplayName(request.getDisplayName());
        entity.setDescription(request.getDescription());
        entity.setStatus(ContextStatus.ACTIVE.name());
        entity.setCreatedBy(request.getCreatedBy());
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        namespaceMapper.insert(entity);
        return ContextViewMapper.toNamespaceResponse(entity);
    }

    public ContextNamespaceResponse getNamespace(Long id) {
        ContextNamespaceEntity entity = requireActiveNamespace(id);
        return ContextViewMapper.toNamespaceResponse(entity);
    }

    public List<ContextNamespaceResponse> listNamespaces(String tenantId,
                                                         String projectCode,
                                                         Long projectId,
                                                         String namespaceType,
                                                         String status) {
        var wrapper = Wrappers.lambdaQuery(ContextNamespaceEntity.class)
                .eq(StringUtils.hasText(tenantId), ContextNamespaceEntity::getTenantId, tenantId)
                .eq(StringUtils.hasText(namespaceType), ContextNamespaceEntity::getNamespaceType, namespaceType)
                .eq(StringUtils.hasText(status), ContextNamespaceEntity::getStatus, status)
                .ne(ContextNamespaceEntity::getStatus, ContextStatus.DELETED.name())
                .orderByDesc(ContextNamespaceEntity::getUpdatedAt);
        applyProjectScopeCandidateFilter(wrapper, projectCode, projectId);
        return namespaceMapper.selectList(wrapper).stream()
                .map(ContextViewMapper::toNamespaceResponse)
                .collect(Collectors.toList());
    }

    public List<ContextNamespaceEntity> listActiveNamespaceEntities(String tenantId,
                                                                      Long namespaceId,
                                                                      String projectCode,
                                                                      Long projectId) {
        if (!StringUtils.hasText(tenantId)) {
            throw new IllegalArgumentException("tenantId is required");
        }
        var wrapper = Wrappers.lambdaQuery(ContextNamespaceEntity.class)
                .eq(ContextNamespaceEntity::getTenantId, tenantId)
                .ne(ContextNamespaceEntity::getStatus, ContextStatus.DELETED.name());
        if (namespaceId != null) {
            wrapper.eq(ContextNamespaceEntity::getId, namespaceId);
        }
        applyProjectScopeCandidateFilter(wrapper, projectCode, projectId);
        return namespaceMapper.selectList(wrapper);
    }

    private void applyProjectScopeCandidateFilter(
            LambdaQueryWrapper<ContextNamespaceEntity> wrapper,
            String projectCode,
            Long projectId) {
        boolean hasProjectCode = StringUtils.hasText(projectCode);
        boolean hasProjectId = projectId != null;
        if (!hasProjectCode && !hasProjectId) {
            return;
        }
        wrapper.and(w -> {
            if (hasProjectCode) {
                w.eq(ContextNamespaceEntity::getProjectCode, projectCode);
            }
            if (hasProjectId) {
                if (hasProjectCode) {
                    w.or();
                }
                w.eq(ContextNamespaceEntity::getProjectId, projectId);
            }
            w.or(empt -> empt.and(blank -> blank
                    .and(c -> c.isNull(ContextNamespaceEntity::getProjectCode)
                            .or()
                            .eq(ContextNamespaceEntity::getProjectCode, ""))
                    .isNull(ContextNamespaceEntity::getProjectId)));
        });
    }

    @Transactional
    public ContextNamespaceResponse markDeleted(Long id) {
        ContextNamespaceEntity entity = requireActiveNamespace(id);
        entity.setStatus(ContextStatus.DELETED.name());
        entity.setDeletedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        namespaceMapper.updateById(entity);
        return ContextViewMapper.toNamespaceResponse(entity);
    }

    public ContextNamespaceEntity requireActiveNamespace(Long id) {
        ContextNamespaceEntity entity = namespaceMapper.selectById(id);
        if (entity == null || ContextStatus.DELETED.name().equals(entity.getStatus())) {
            throw new IllegalArgumentException("Context namespace not found: " + id);
        }
        return entity;
    }

    public ContextNamespaceEntity resolveNamespaceByKey(String namespaceKey) {
        if (!StringUtils.hasText(namespaceKey)) {
            throw new IllegalArgumentException("namespaceKey is required");
        }
        ContextNamespaceEntity entity = namespaceMapper.selectOne(
                Wrappers.lambdaQuery(ContextNamespaceEntity.class)
                        .eq(ContextNamespaceEntity::getNamespaceKey, namespaceKey.trim())
                        .ne(ContextNamespaceEntity::getStatus, ContextStatus.DELETED.name())
                        .last("LIMIT 1"));
        if (entity == null) {
            throw new IllegalArgumentException("Context namespace not found for key: " + namespaceKey);
        }
        return entity;
    }

    ContextNamespaceEntity resolveNamespace(ContextItemCreateRequest request) {
        if (request.getNamespaceId() != null) {
            return requireActiveNamespace(request.getNamespaceId());
        }
        if (!StringUtils.hasText(request.getNamespaceKey())) {
            throw new IllegalArgumentException("namespaceId or namespaceKey is required");
        }
        ContextNamespaceEntity entity = namespaceMapper.selectOne(
                Wrappers.lambdaQuery(ContextNamespaceEntity.class)
                        .eq(ContextNamespaceEntity::getNamespaceKey, request.getNamespaceKey().trim())
                        .ne(ContextNamespaceEntity::getStatus, ContextStatus.DELETED.name())
                        .last("LIMIT 1"));
        if (entity == null) {
            throw new IllegalArgumentException("Context namespace not found for key: " + request.getNamespaceKey());
        }
        return entity;
    }
}
