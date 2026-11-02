package com.enterprise.ai.control.compliance;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.control.identity.ExternalUserBindingEntity;
import com.enterprise.ai.control.identity.ExternalUserBindingMapper;
import com.enterprise.ai.control.platform.PlatformEmbedSessionEntity;
import com.enterprise.ai.control.platform.PlatformEmbedSessionMapper;
import org.springframework.stereotype.Service;

/** Conservative evidence that an unscoped Runtime user id may refer to a business account. */
@Service
public class BusinessIdentityErasureGuard {
    private final ExternalUserBindingMapper bindings;
    private final PlatformEmbedSessionMapper sessions;

    public BusinessIdentityErasureGuard(ExternalUserBindingMapper bindings, PlatformEmbedSessionMapper sessions) {
        this.bindings = bindings;
        this.sessions = sessions;
    }

    public boolean hasPotentialBusinessIdentity(String tenant, String userId) {
        // Include disabled bindings and expired sessions: Runtime data can outlive their active status.
        if (bindings.selectCount(Wrappers.<ExternalUserBindingEntity>lambdaQuery()
                .eq(ExternalUserBindingEntity::getTenantId, tenant)
                .eq(ExternalUserBindingEntity::getExternalUserId, userId)) > 0) return true;
        var query = Wrappers.<PlatformEmbedSessionEntity>lambdaQuery()
                .and(q -> q.eq(PlatformEmbedSessionEntity::getTenantId, tenant)
                        .or("default".equals(tenant), nested -> nested.isNull(PlatformEmbedSessionEntity::getTenantId)))
                .and(q -> q.eq(PlatformEmbedSessionEntity::getExternalUserId, userId)
                        .or().eq(PlatformEmbedSessionEntity::getGlobalUserId, userId));
        return sessions.selectCount(query) > 0;
    }
}
