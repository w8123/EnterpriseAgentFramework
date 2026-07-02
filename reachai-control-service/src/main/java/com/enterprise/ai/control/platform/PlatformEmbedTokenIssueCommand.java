package com.enterprise.ai.control.platform;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlatformEmbedTokenIssueCommand {

    private String tenantId;
    private String appId;
    private String projectCode;
    private String agentId;
    private String pageKey;
    private String pageInstanceId;
    private String route;
    private String origin;
    private Integer ttlSeconds;
    private BusinessPrincipal principal;

    public record BusinessPrincipal(
            String externalUserId,
            String globalUserId,
            String userName,
            List<String> roles,
            Map<String, Object> attributes) {
    }
}
