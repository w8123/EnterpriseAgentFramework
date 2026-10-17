package com.enterprise.ai.control.identity;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

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
}
