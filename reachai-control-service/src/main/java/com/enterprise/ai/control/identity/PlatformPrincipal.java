package com.enterprise.ai.control.identity;

import lombok.Value;

/** Minimal immutable identity exposed to authenticated application code; it contains no credential fields. */
@Value
public class PlatformPrincipal {
    Long id;
    String username;
    String displayName;

    public static PlatformPrincipal fromUser(PlatformUserEntity user) {
        return user == null ? null : new PlatformPrincipal(user.getId(), user.getUsername(), user.getDisplayName());
    }
}
