package com.enterprise.ai.agent.registry;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.agent.tool.log.ToolExecutionContext;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionEntity;
import com.enterprise.ai.reach.sdk.auth.ReachAiInvocationClaims;
import com.enterprise.ai.reach.sdk.auth.ReachAiInvocationToken;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class ReachAiInvocationTokenService {

    private final RegistryCredentialMapper credentialMapper;

    @Value("${reachai.invocation-token.ttl-seconds:120}")
    private int ttlSeconds;

    public String createToken(ToolDefinitionEntity definition, ToolExecutionContext context) {
        if (definition == null || context == null) {
            return null;
        }
        String projectCode = firstNonBlank(definition.getProjectCode(), context.getProjectCode());
        if (!StringUtils.hasText(projectCode)) {
            throw new IllegalStateException("ReachAI invocation token requires projectCode");
        }
        RegistryCredentialEntity credential = credentialMapper.selectOne(Wrappers.<RegistryCredentialEntity>lambdaQuery()
                .eq(RegistryCredentialEntity::getProjectCode, projectCode.trim())
                .eq(RegistryCredentialEntity::getStatus, "ACTIVE")
                .orderByDesc(RegistryCredentialEntity::getUpdatedAt)
                .last("LIMIT 1"));
        if (credential == null || !StringUtils.hasText(credential.getAppSecret())) {
            throw new IllegalStateException("ReachAI registry credential is missing for project: " + projectCode);
        }
        if (credential.getExpiresAt() != null && credential.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new IllegalStateException("ReachAI registry credential is expired for project: " + projectCode);
        }
        ReachAiInvocationClaims claims = ReachAiInvocationClaims.builder()
                .projectCode(projectCode)
                .appKey(credential.getAppKey())
                .capabilityName(definition.getName())
                .tenantId(context.getTenantId())
                .externalUserId(firstNonBlank(context.getExternalUserId(), context.getUserId()))
                .globalUserId(context.getGlobalUserId())
                .roles(context.getRoles())
                .agentId(firstNonBlank(context.getAgentId(), context.getAgentName()))
                .sessionId(context.getSessionId())
                .traceId(context.getTraceId())
                .pageInstanceId(context.getPageInstanceId())
                .origin(context.getOrigin())
                .route(context.getRoute())
                .build();
        return ReachAiInvocationToken.sign(
                credential.getAppSecret(),
                claims,
                System.currentTimeMillis(),
                Math.max(30, ttlSeconds));
    }

    private String firstNonBlank(String first, String second) {
        return StringUtils.hasText(first) ? first.trim() : StringUtils.hasText(second) ? second.trim() : null;
    }
}
