package com.enterprise.ai.personalmemory;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Bounded semantic projection policy for owner-scoped personal memory. */
@Component
@ConfigurationProperties(prefix = "reachai.personal-memory.embedding")
public class PersonalMemoryEmbeddingProperties implements InitializingBean {

    public enum Mode {
        LEXICAL,
        VECTOR,
        HYBRID
    }

    private Mode mode = Mode.LEXICAL;
    private String modelInstanceId;
    private int candidateLimit = 1000;
    private int batchSize = 32;
    private long fixedDelayMs = 1000;
    private long initialDelayMs = 5000;
    private long observabilityFixedDelayMs = 30_000;
    private long observabilityInitialDelayMs = 10_000;
    private long claimSeconds = 120;
    private int maxAttempts = 12;
    private long initialBackoffSeconds = 5;
    private long maxBackoffSeconds = 3600;
    private float minScore = 0.5f;
    private float vectorWeight = 0.75f;
    private int maxTextChars = 4000;
    private int maxQueryChars = 4000;

    @Override
    public void afterPropertiesSet() {
        if (mode == null) {
            throw new IllegalStateException("personal-memory embedding mode is required");
        }
        if (mode != Mode.LEXICAL && !StringUtils.hasText(modelInstanceId)) {
            throw new IllegalStateException(
                    "personal-memory embedding modelInstanceId is required for VECTOR or HYBRID mode");
        }
        if (candidateLimit < 1 || candidateLimit > 2000) {
            throw new IllegalStateException("personal-memory embedding candidateLimit must be between 1 and 2000");
        }
        if (batchSize < 1 || batchSize > 200) {
            throw new IllegalStateException("personal-memory embedding batchSize must be between 1 and 200");
        }
        if (fixedDelayMs < 250 || fixedDelayMs > 3_600_000) {
            throw new IllegalStateException("personal-memory embedding fixedDelayMs must be between 250 and 3600000");
        }
        if (initialDelayMs < 0 || initialDelayMs > 3_600_000) {
            throw new IllegalStateException("personal-memory embedding initialDelayMs must be between 0 and 3600000");
        }
        if (observabilityFixedDelayMs < 1000 || observabilityFixedDelayMs > 3_600_000) {
            throw new IllegalStateException(
                    "personal-memory embedding observabilityFixedDelayMs must be between 1000 and 3600000");
        }
        if (observabilityInitialDelayMs < 0 || observabilityInitialDelayMs > 3_600_000) {
            throw new IllegalStateException(
                    "personal-memory embedding observabilityInitialDelayMs must be between 0 and 3600000");
        }
        if (claimSeconds < 10 || claimSeconds > 3600) {
            throw new IllegalStateException("personal-memory embedding claimSeconds must be between 10 and 3600");
        }
        if (maxAttempts < 1 || maxAttempts > 100) {
            throw new IllegalStateException("personal-memory embedding maxAttempts must be between 1 and 100");
        }
        if (initialBackoffSeconds < 1 || maxBackoffSeconds < initialBackoffSeconds
                || maxBackoffSeconds > 86_400) {
            throw new IllegalStateException("personal-memory embedding retry backoff is invalid");
        }
        if (!Float.isFinite(minScore) || minScore < 0 || minScore > 1) {
            throw new IllegalStateException("personal-memory embedding minScore must be between 0 and 1");
        }
        if (!Float.isFinite(vectorWeight) || vectorWeight < 0 || vectorWeight > 1) {
            throw new IllegalStateException("personal-memory embedding vectorWeight must be between 0 and 1");
        }
        if (maxTextChars < 128 || maxTextChars > 32_000) {
            throw new IllegalStateException("personal-memory embedding maxTextChars must be between 128 and 32000");
        }
        if (maxQueryChars < 128 || maxQueryChars > 32_000) {
            throw new IllegalStateException("personal-memory embedding maxQueryChars must be between 128 and 32000");
        }
        if (StringUtils.hasText(modelInstanceId)) {
            modelInstanceId = modelInstanceId.trim();
            if (modelInstanceId.length() > 64) {
                throw new IllegalStateException(
                        "personal-memory embedding modelInstanceId must not exceed 64 characters");
            }
        }
    }

    public boolean semanticEnabled() {
        return mode != Mode.LEXICAL;
    }

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    public String getModelInstanceId() {
        return modelInstanceId;
    }

    public void setModelInstanceId(String modelInstanceId) {
        this.modelInstanceId = modelInstanceId;
    }

    public int getCandidateLimit() {
        return candidateLimit;
    }

    public void setCandidateLimit(int candidateLimit) {
        this.candidateLimit = candidateLimit;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public long getFixedDelayMs() {
        return fixedDelayMs;
    }

    public void setFixedDelayMs(long fixedDelayMs) {
        this.fixedDelayMs = fixedDelayMs;
    }

    public long getInitialDelayMs() {
        return initialDelayMs;
    }

    public void setInitialDelayMs(long initialDelayMs) {
        this.initialDelayMs = initialDelayMs;
    }

    public long getObservabilityFixedDelayMs() {
        return observabilityFixedDelayMs;
    }

    public void setObservabilityFixedDelayMs(long observabilityFixedDelayMs) {
        this.observabilityFixedDelayMs = observabilityFixedDelayMs;
    }

    public long getObservabilityInitialDelayMs() {
        return observabilityInitialDelayMs;
    }

    public void setObservabilityInitialDelayMs(long observabilityInitialDelayMs) {
        this.observabilityInitialDelayMs = observabilityInitialDelayMs;
    }

    public long getClaimSeconds() {
        return claimSeconds;
    }

    public void setClaimSeconds(long claimSeconds) {
        this.claimSeconds = claimSeconds;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public long getInitialBackoffSeconds() {
        return initialBackoffSeconds;
    }

    public void setInitialBackoffSeconds(long initialBackoffSeconds) {
        this.initialBackoffSeconds = initialBackoffSeconds;
    }

    public long getMaxBackoffSeconds() {
        return maxBackoffSeconds;
    }

    public void setMaxBackoffSeconds(long maxBackoffSeconds) {
        this.maxBackoffSeconds = maxBackoffSeconds;
    }

    public float getMinScore() {
        return minScore;
    }

    public void setMinScore(float minScore) {
        this.minScore = minScore;
    }

    public float getVectorWeight() {
        return vectorWeight;
    }

    public void setVectorWeight(float vectorWeight) {
        this.vectorWeight = vectorWeight;
    }

    public int getMaxTextChars() {
        return maxTextChars;
    }

    public void setMaxTextChars(int maxTextChars) {
        this.maxTextChars = maxTextChars;
    }

    public int getMaxQueryChars() {
        return maxQueryChars;
    }

    public void setMaxQueryChars(int maxQueryChars) {
        this.maxQueryChars = maxQueryChars;
    }
}
