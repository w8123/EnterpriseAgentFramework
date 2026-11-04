package com.enterprise.ai.control.platform;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;

/** Platform-owned read used by erasure: has any embed session (live or expired) carried this user id? */
@Component
public class PlatformEmbedSessionErasureLookup {
    private final PlatformEmbedSessionMapper sessions;

    public PlatformEmbedSessionErasureLookup(PlatformEmbedSessionMapper sessions) {
        this.sessions = sessions;
    }

    /** Includes expired sessions; the default tenant also matches legacy rows without a tenant. */
    public boolean anySession(String tenant, String userId) {
        var query = Wrappers.<PlatformEmbedSessionEntity>lambdaQuery()
                .and(q -> q.eq(PlatformEmbedSessionEntity::getTenantId, tenant)
                        .or("default".equals(tenant), nested -> nested.isNull(PlatformEmbedSessionEntity::getTenantId)))
                .and(q -> q.eq(PlatformEmbedSessionEntity::getExternalUserId, userId)
                        .or().eq(PlatformEmbedSessionEntity::getGlobalUserId, userId));
        return sessions.selectCount(query) > 0;
    }
}
