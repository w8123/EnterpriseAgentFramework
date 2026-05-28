package com.enterprise.ai.agent.identity;

import java.time.Instant;

public record EmbedTokenIssueResult(String token, long expiresIn, Instant expiresAt) {
}
