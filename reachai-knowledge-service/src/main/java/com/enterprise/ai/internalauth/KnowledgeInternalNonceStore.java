package com.enterprise.ai.internalauth;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Shared replay store for signed Knowledge ingress across all replicas. */
@Component
public class KnowledgeInternalNonceStore {

    private final StringRedisTemplate redisTemplate;

    public KnowledgeInternalNonceStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /** Redis unavailability fails closed; accepting a replay is never a fallback. */
    public boolean tryConsume(String caller, String nonce, Duration ttl) {
        try {
            return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(
                    "reachai:knowledge:internal-auth:nonce:" + caller + ":" + nonce,
                    "1",
                    ttl));
        } catch (RuntimeException unavailable) {
            return false;
        }
    }
}
