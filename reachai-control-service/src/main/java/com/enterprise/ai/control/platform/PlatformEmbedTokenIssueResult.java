package com.enterprise.ai.control.platform;

import java.time.Instant;

public record PlatformEmbedTokenIssueResult(String token, long expiresIn, Instant expiresAt) {
}
