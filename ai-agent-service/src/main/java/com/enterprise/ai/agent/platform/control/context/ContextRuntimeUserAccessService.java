package com.enterprise.ai.agent.platform.control.context;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.agent.platform.control.auth.PlatformPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class ContextRuntimeUserAccessService {

    public static final String REVIEW_PERMISSION = "context:runtime-user:review";

    private static final String ACTIVE = "ACTIVE";

    private final ContextRuntimeUserMappingMapper mappingMapper;

    public boolean canReviewRuntimeUser(PlatformPrincipal principal,
                                        String tenantId,
                                        String runtimeUserId,
                                        String projectCode,
                                        Long projectId) {
        if (!hasReviewPermission(principal)
                || !StringUtils.hasText(tenantId)
                || !StringUtils.hasText(runtimeUserId)) {
            return false;
        }
        var wrapper = Wrappers.lambdaQuery(ContextRuntimeUserMappingEntity.class)
                .eq(ContextRuntimeUserMappingEntity::getTenantId, tenantId.trim())
                .eq(ContextRuntimeUserMappingEntity::getPlatformUserId, principal.userId())
                .eq(ContextRuntimeUserMappingEntity::getRuntimeUserId, runtimeUserId.trim())
                .eq(ContextRuntimeUserMappingEntity::getStatus, ACTIVE)
                .isNull(ContextRuntimeUserMappingEntity::getDeletedAt);
        boolean hasProjectScope = StringUtils.hasText(projectCode) || projectId != null;
        if (hasProjectScope) {
            String normalizedProjectCode = StringUtils.hasText(projectCode) ? projectCode.trim() : null;
            wrapper.and(project -> project
                    .and(tenantWide -> tenantWide
                            .isNull(ContextRuntimeUserMappingEntity::getProjectCode)
                            .isNull(ContextRuntimeUserMappingEntity::getProjectId))
                    .or(match -> {
                        if (StringUtils.hasText(normalizedProjectCode) && projectId != null) {
                            match.and(projectIdentity -> projectIdentity
                                    .eq(ContextRuntimeUserMappingEntity::getProjectCode, normalizedProjectCode)
                                    .or()
                                    .eq(ContextRuntimeUserMappingEntity::getProjectId, projectId));
                        } else if (StringUtils.hasText(normalizedProjectCode)) {
                            match.eq(ContextRuntimeUserMappingEntity::getProjectCode, normalizedProjectCode);
                        } else {
                            match.eq(ContextRuntimeUserMappingEntity::getProjectId, projectId);
                        }
                    }));
        } else {
            wrapper.isNull(ContextRuntimeUserMappingEntity::getProjectCode)
                    .isNull(ContextRuntimeUserMappingEntity::getProjectId);
        }
        Long count = mappingMapper.selectCount(wrapper);
        return count != null && count > 0;
    }

    private boolean hasReviewPermission(PlatformPrincipal principal) {
        if (principal == null || principal.userId() == null || principal.permissions() == null) {
            return false;
        }
        return principal.permissions().contains("*")
                || principal.permissions().contains("platform:admin")
                || principal.permissions().contains(REVIEW_PERMISSION);
    }
}
