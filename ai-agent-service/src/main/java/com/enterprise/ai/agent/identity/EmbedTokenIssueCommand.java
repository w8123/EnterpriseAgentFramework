package com.enterprise.ai.agent.identity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmbedTokenIssueCommand {

    private String tenantId;

    private String appId;

    private String projectCode;

    private String agentId;

    private String pageInstanceId;

    private String route;

    private String origin;

    private Integer ttlSeconds;

    private BusinessPrincipal principal;
}
