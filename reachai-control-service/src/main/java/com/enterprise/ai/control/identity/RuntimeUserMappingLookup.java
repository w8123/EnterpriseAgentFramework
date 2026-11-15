package com.enterprise.ai.control.identity;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Objects;

/** Read side of the platform-user to Runtime-user mapping, for other Control modules. */
@Component
public class RuntimeUserMappingLookup {

    private final RuntimeUserMappingMapper mappingMapper;

    public RuntimeUserMappingLookup(RuntimeUserMappingMapper mappingMapper) {
        this.mappingMapper = mappingMapper;
    }

    /** Distinct ACTIVE Runtime user ids mapped to this platform user in the tenant, newest first. */
    public List<String> activeRuntimeUserIds(String tenantId, Long platformUserId) {
        return mappingMapper.selectList(
                        Wrappers.<RuntimeUserMappingEntity>lambdaQuery()
                                .eq(RuntimeUserMappingEntity::getTenantId, tenantId)
                                .eq(RuntimeUserMappingEntity::getPlatformUserId, platformUserId)
                                .eq(RuntimeUserMappingEntity::getStatus, "ACTIVE")
                                .eq(RuntimeUserMappingEntity::getActiveMarker, 1)
                                .orderByDesc(RuntimeUserMappingEntity::getUpdatedAt))
                .stream()
                .map(RuntimeUserMappingEntity::getRuntimeUserId)
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
    }

    /**
     * Distinct business accounts this platform user acts as in one business system. A mapping counts
     * only for that exact system; a mapping without any project never does.
     */
    public List<String> activeExternalUserIds(String tenantId, Long platformUserId, Long projectId, String projectCode) {
        if (platformUserId == null || !StringUtils.hasText(projectCode)) return List.of();
        String code = projectCode.trim();
        return mappingMapper.selectList(
                        Wrappers.<RuntimeUserMappingEntity>lambdaQuery()
                                .eq(RuntimeUserMappingEntity::getTenantId, tenantId)
                                .eq(RuntimeUserMappingEntity::getPlatformUserId, platformUserId)
                                .eq(RuntimeUserMappingEntity::getStatus, "ACTIVE")
                                .eq(RuntimeUserMappingEntity::getActiveMarker, 1))
                .stream()
                .filter(mapping -> sameProject(mapping, projectId, code))
                .map(RuntimeUserMappingEntity::getExternalUserId)
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
    }

    private static boolean sameProject(RuntimeUserMappingEntity mapping, Long projectId, String projectCode) {
        boolean hasCode = StringUtils.hasText(mapping.getProjectCode());
        if (!hasCode && mapping.getProjectId() == null) return false;
        if (hasCode && !projectCode.equalsIgnoreCase(mapping.getProjectCode().trim())) return false;
        return mapping.getProjectId() == null || projectId == null || Objects.equals(mapping.getProjectId(), projectId);
    }
}
