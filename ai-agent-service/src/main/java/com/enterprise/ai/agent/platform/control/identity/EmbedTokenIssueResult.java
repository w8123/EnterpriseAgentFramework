package com.enterprise.ai.agent.platform.control.identity;

import java.time.Instant;

public record EmbedTokenIssueResult(String token, long expiresIn, Instant expiresAt) {
}
