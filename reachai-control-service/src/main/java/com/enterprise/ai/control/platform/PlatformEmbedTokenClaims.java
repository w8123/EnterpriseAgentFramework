package com.enterprise.ai.control.platform;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class PlatformEmbedTokenClaims {

    private String issuer;
    private String audience;
    private String tenantId;
    private String appId;
    private String projectCode;
    private String agentId;
    private String externalUserId;
    private String globalUserId;
    private String userName;
    private String pageKey;
    private String pageInstanceId;
    private String route;
    private String origin;
    private String jti;
    private long issuedAt;
    private long expiresAt;
    private List<String> roles = List.of();
    private Map<String, Object> attributes = Map.of();
}
