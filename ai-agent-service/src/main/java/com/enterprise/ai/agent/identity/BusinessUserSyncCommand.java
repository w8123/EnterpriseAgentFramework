package com.enterprise.ai.agent.identity;

import java.util.List;
import java.util.Map;

public record BusinessUserSyncCommand(
        String globalUserId,
        String externalUserId,
        String userName,
        String email,
        String mobile,
        String deptId,
        String deptName,
        List<String> roles,
        Map<String, Object> attributes) {
    public BusinessUserSyncCommand {
        roles = roles == null ? List.of() : roles;
        attributes = attributes == null ? Map.of() : attributes;
    }
}
