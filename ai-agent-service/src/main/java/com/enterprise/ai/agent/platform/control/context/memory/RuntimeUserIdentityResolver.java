package com.enterprise.ai.agent.platform.control.context.memory;

import com.enterprise.ai.agent.platform.control.identity.EmbedSessionEntity;
import com.enterprise.ai.agent.platform.control.identity.EmbedTokenClaims;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
public class RuntimeUserIdentityResolver {

    private final ContextMemoryCandidateService candidateService;

    public RuntimeUserIdentity resolve(EmbedSessionEntity session, EmbedTokenClaims claims) {
        if (session == null) {
            throw new IllegalArgumentException("embed session is required");
        }
        if (claims == null) {
            throw new IllegalArgumentException("embed token claims are required");
        }
        String tenantId = firstText(session.getTenantId(), claims.getTenantId());
        if (!StringUtils.hasText(tenantId)) {
            throw new IllegalArgumentException("runtime tenantId is required");
        }
        String globalUserId = firstText(session.getGlobalUserId(), claims.getGlobalUserId());
        String externalUserId = firstText(session.getExternalUserId(), claims.getExternalUserId());
        String userId = candidateService.resolveUserId(globalUserId, externalUserId, null);
        if (!StringUtils.hasText(userId)) {
            throw new IllegalArgumentException("runtime userId is required");
        }
        String projectCode = firstText(session.getProjectCode(), claims.getProjectCode());
        return new RuntimeUserIdentity(
                tenantId.trim(),
                userId.trim(),
                globalUserId,
                externalUserId,
                projectCode);
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }
}
