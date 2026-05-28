package com.enterprise.ai.agent.identity;

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
public class BusinessPrincipal {

    private String tenantId;

    private String appId;

    private String externalUserId;

    private String globalUserId;

    private String userName;

    private String deptId;

    private String deptName;

    @Builder.Default
    private List<String> roles = List.of();

    @Builder.Default
    private Map<String, Object> attributes = Map.of();
}
