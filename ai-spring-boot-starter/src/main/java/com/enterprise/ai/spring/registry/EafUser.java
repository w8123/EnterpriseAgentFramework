package com.enterprise.ai.spring.registry;

import java.util.List;
import java.util.Map;

public record EafUser(
        String externalUserId,
        String globalUserId,
        String userName,
        String deptId,
        String deptName,
        List<String> roles,
        Map<String, Object> attributes
) {
    public EafUser {
        roles = roles == null ? List.of() : roles;
        attributes = attributes == null ? Map.of() : attributes;
    }
}
