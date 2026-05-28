package com.enterprise.ai.spring.registry;

import java.util.List;
import java.util.Map;

public record EafExternalUser(
        String globalUserId,
        String externalUserId,
        String userName,
        String email,
        String mobile,
        String deptId,
        String deptName,
        List<String> roles,
        Map<String, Object> attributes
) {
    public EafExternalUser {
        roles = roles == null ? List.of() : roles;
        attributes = attributes == null ? Map.of() : attributes;
    }
}
