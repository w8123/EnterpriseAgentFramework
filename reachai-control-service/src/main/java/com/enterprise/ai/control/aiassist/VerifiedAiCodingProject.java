package com.enterprise.ai.control.aiassist;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Created only after project-key verification; the credential itself never crosses the owner boundary. */
public record VerifiedAiCodingProject(Long projectId, String actorId) {
    public static final String REQUEST_ATTRIBUTE = "reachai.ai-coding.verified-project";

    public VerifiedAiCodingProject {
        if (projectId == null || projectId < 1 || actorId == null || !actorId.matches("ai-coding-[a-f0-9]{64}")) {
            throw new IllegalArgumentException("invalid verified AI Coding project");
        }
    }
    static VerifiedAiCodingProject fromVerifiedKey(Long projectId, String key) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("verified project key is required");
        try {
            return new VerifiedAiCodingProject(projectId, "ai-coding-" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(key.trim().getBytes(StandardCharsets.UTF_8))));
        } catch (java.security.NoSuchAlgorithmException unavailable) { throw new IllegalStateException(unavailable); }
    }
}
