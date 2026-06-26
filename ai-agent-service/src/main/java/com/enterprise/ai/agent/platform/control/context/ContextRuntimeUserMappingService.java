package com.enterprise.ai.agent.platform.control.context;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ContextRuntimeUserMappingService {

    private static final String ACTIVE = "ACTIVE";
    private static final String DELETED = "DELETED";

    private final ContextRuntimeUserMappingMapper mappingMapper;

    public List<ContextRuntimeUserMappingResponse> listMappings(ContextRuntimeUserMappingQueryRequest query) {
        if (query == null || !StringUtils.hasText(query.getTenantId())) {
            throw new IllegalArgumentException("tenantId is required");
        }
        int limit = query.getLimit() == null || query.getLimit() <= 0 ? 50 : Math.min(query.getLimit(), 200);
        String status = StringUtils.hasText(query.getStatus()) ? query.getStatus().trim().toUpperCase() : ACTIVE;
        return mappingMapper.selectList(Wrappers.lambdaQuery(ContextRuntimeUserMappingEntity.class)
                        .eq(ContextRuntimeUserMappingEntity::getTenantId, query.getTenantId().trim())
                        .eq(query.getPlatformUserId() != null,
                                ContextRuntimeUserMappingEntity::getPlatformUserId, query.getPlatformUserId())
                        .eq(StringUtils.hasText(query.getRuntimeUserId()),
                                ContextRuntimeUserMappingEntity::getRuntimeUserId, trimToNull(query.getRuntimeUserId()))
                        .eq(StringUtils.hasText(query.getProjectCode()),
                                ContextRuntimeUserMappingEntity::getProjectCode, trimToNull(query.getProjectCode()))
                        .eq(query.getProjectId() != null,
                                ContextRuntimeUserMappingEntity::getProjectId, query.getProjectId())
                        .eq(ContextRuntimeUserMappingEntity::getStatus, status)
                        .isNull(ContextRuntimeUserMappingEntity::getDeletedAt)
                        .orderByDesc(ContextRuntimeUserMappingEntity::getUpdatedAt)
                        .last("LIMIT " + limit))
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public ContextRuntimeUserMappingResponse createMapping(ContextRuntimeUserMappingCreateRequest request) {
        validateCreateRequest(request);
        String tenantId = request.getTenantId().trim();
        String runtimeUserId = firstText(request.getRuntimeUserId(), request.getGlobalUserId(),
                request.getExternalUserId());
        ContextRuntimeUserMappingEntity existing = mappingMapper.selectOne(duplicateWrapper(
                tenantId, request.getPlatformUserId(), runtimeUserId, request.getProjectCode(), request.getProjectId()));
        if (existing != null) {
            return toResponse(existing);
        }
        LocalDateTime now = LocalDateTime.now();
        ContextRuntimeUserMappingEntity entity = new ContextRuntimeUserMappingEntity();
        entity.setTenantId(tenantId);
        entity.setPlatformUserId(request.getPlatformUserId());
        entity.setRuntimeUserId(runtimeUserId);
        entity.setGlobalUserId(trimToNull(request.getGlobalUserId()));
        entity.setExternalUserId(trimToNull(request.getExternalUserId()));
        entity.setProjectId(request.getProjectId());
        entity.setProjectCode(trimToNull(request.getProjectCode()));
        entity.setStatus(ACTIVE);
        entity.setCreatedBy(trimToNull(request.getCreatedBy()));
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        mappingMapper.insert(entity);
        return toResponse(entity);
    }

    @Transactional
    public ContextRuntimeUserMappingResponse deleteMapping(Long id, String actorId) {
        if (id == null || id <= 0) {
            throw new IllegalArgumentException("mapping id is required");
        }
        ContextRuntimeUserMappingEntity entity = mappingMapper.selectById(id);
        if (entity == null || DELETED.equals(entity.getStatus())) {
            throw new IllegalArgumentException("Runtime user mapping not found: " + id);
        }
        entity.setStatus(DELETED);
        entity.setDeletedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        mappingMapper.updateById(entity);
        return toResponse(entity);
    }

    private void validateCreateRequest(ContextRuntimeUserMappingCreateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        if (!StringUtils.hasText(request.getTenantId())) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (request.getPlatformUserId() == null || request.getPlatformUserId() <= 0) {
            throw new IllegalArgumentException("platformUserId is required");
        }
        if (!StringUtils.hasText(firstText(request.getRuntimeUserId(), request.getGlobalUserId(),
                request.getExternalUserId()))) {
            throw new IllegalArgumentException("runtimeUserId, globalUserId, or externalUserId is required");
        }
    }

    private LambdaQueryWrapper<ContextRuntimeUserMappingEntity> duplicateWrapper(String tenantId,
                                                                                 Long platformUserId,
                                                                                 String runtimeUserId,
                                                                                 String projectCode,
                                                                                 Long projectId) {
        LambdaQueryWrapper<ContextRuntimeUserMappingEntity> wrapper =
                Wrappers.lambdaQuery(ContextRuntimeUserMappingEntity.class)
                        .eq(ContextRuntimeUserMappingEntity::getTenantId, tenantId)
                        .eq(ContextRuntimeUserMappingEntity::getPlatformUserId, platformUserId)
                        .eq(ContextRuntimeUserMappingEntity::getRuntimeUserId, runtimeUserId)
                        .eq(ContextRuntimeUserMappingEntity::getStatus, ACTIVE)
                        .isNull(ContextRuntimeUserMappingEntity::getDeletedAt);
        if (StringUtils.hasText(projectCode)) {
            wrapper.eq(ContextRuntimeUserMappingEntity::getProjectCode, projectCode.trim());
        } else {
            wrapper.isNull(ContextRuntimeUserMappingEntity::getProjectCode);
        }
        if (projectId != null) {
            wrapper.eq(ContextRuntimeUserMappingEntity::getProjectId, projectId);
        } else {
            wrapper.isNull(ContextRuntimeUserMappingEntity::getProjectId);
        }
        wrapper.last("LIMIT 1");
        return wrapper;
    }

    private ContextRuntimeUserMappingResponse toResponse(ContextRuntimeUserMappingEntity entity) {
        ContextRuntimeUserMappingResponse response = new ContextRuntimeUserMappingResponse();
        response.setId(entity.getId());
        response.setTenantId(entity.getTenantId());
        response.setPlatformUserId(entity.getPlatformUserId());
        response.setRuntimeUserId(entity.getRuntimeUserId());
        response.setGlobalUserId(entity.getGlobalUserId());
        response.setExternalUserId(entity.getExternalUserId());
        response.setProjectId(entity.getProjectId());
        response.setProjectCode(entity.getProjectCode());
        response.setStatus(entity.getStatus());
        response.setCreatedBy(entity.getCreatedBy());
        response.setCreatedAt(entity.getCreatedAt());
        response.setUpdatedAt(entity.getUpdatedAt());
        response.setDeletedAt(entity.getDeletedAt());
        return response;
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
