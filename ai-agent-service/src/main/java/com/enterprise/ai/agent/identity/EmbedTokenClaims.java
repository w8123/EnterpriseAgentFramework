package com.enterprise.ai.agent.identity;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class EmbedTokenClaims {

    private String issuer;

    private String audience;

    private String tenantId;

    private String appId;

    private String projectCode;

    private String agentId;

    private String externalUserId;

    private String globalUserId;

    private String userName;

    private String deptId;

    private String deptName;

    private String pageInstanceId;

    private String route;

    private String origin;

    private String jti;

    private long issuedAt;

    private long expiresAt;

    private List<String> roles = List.of();

    private Map<String, Object> attributes = Map.of();

    public BusinessPrincipal toPrincipal() {
        return BusinessPrincipal.builder()
                .tenantId(tenantId)
                .appId(appId)
                .externalUserId(externalUserId)
                .globalUserId(globalUserId)
                .userName(userName)
                .deptId(deptId)
                .deptName(deptName)
                .roles(roles == null ? List.of() : roles)
                .attributes(attributes == null ? Map.of() : attributes)
                .build();
    }
}
