package com.enterprise.ai.agent.tool.governance;

import com.enterprise.ai.agent.config.ToolRateLimitProperties;
import com.enterprise.ai.agent.tool.log.ToolExecutionContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * Redis 固定窗口限流器，用于防止单个 Tool / Skill 被异常 prompt 高频调用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolRateLimiter {

    private static final String KEY_PREFIX = "tool:rate:";

    private final StringRedisTemplate redisTemplate;
    private final ToolRateLimitProperties properties;

    public RateLimitDecision check(String toolName, ToolExecutionContext context) {
        if (!properties.isEnabled()) {
            return RateLimitDecision.allow();
        }
        String normalizedTool = normalize(toolName, "unknown");
        String key = buildKey(normalizedTool, context);
        int windowSeconds = Math.max(1, properties.getWindowSeconds());
        int capacity = Math.max(1, properties.getCapacity());
        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redisTemplate.expire(key, Duration.ofSeconds(windowSeconds));
            }
            if (count != null && count > capacity) {
                return RateLimitDecision.deny(
                        "Tool 调用触发限流: " + normalizedTool
                                + "，窗口 " + windowSeconds + "s 内最多 " + capacity + " 次");
            }
            return RateLimitDecision.allow();
        } catch (Exception ex) {
            log.warn("[ToolRateLimiter] Redis 限流检查失败，放行本次调用: key={}, err={}", key, ex.toString());
            return RateLimitDecision.allow();
        }
    }

    private String buildKey(String toolName, ToolExecutionContext context) {
        StringBuilder key = new StringBuilder(KEY_PREFIX).append(toolName);
        if (properties.isPerUser()) {
            key.append(":u:").append(normalize(context == null ? null : context.getUserId(), "anonymous"));
        }
        if (properties.isPerRoles()) {
            List<String> roles = context == null ? null : context.getRoles();
            key.append(":r:").append(roles == null || roles.isEmpty() ? "none" : normalize(String.join(",", roles), "none"));
        }
        return key.toString();
    }

    private static String normalize(String raw, String fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        return raw.trim().replaceAll("[^a-zA-Z0-9._:-]", "_");
    }

    public record RateLimitDecision(boolean allowed, String message) {
        static RateLimitDecision allow() {
            return new RateLimitDecision(true, null);
        }

        static RateLimitDecision deny(String message) {
            return new RateLimitDecision(false, message);
        }
    }
}
