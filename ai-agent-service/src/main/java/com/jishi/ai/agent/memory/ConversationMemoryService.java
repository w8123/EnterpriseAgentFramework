package com.jishi.ai.agent.memory;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 会话记忆管理服务 — 基于 Redis 的短期上下文窗口
 * <p>
 * 为每个 sessionId 维护一个有序的消息列表（role + content），
 * 支持上下文窗口大小限制和过期自动清理。
 * <p>
 * 存储结构：Redis String，Key = "chat:memory:{sessionId}"，Value = JSON Array
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationMemoryService {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String KEY_PREFIX = "chat:memory:";

    @Value("${memory.max-messages:20}")
    private int maxMessages;

    @Value("${memory.ttl-hours:24}")
    private int ttlHours;

    /**
     * 获取会话的历史消息
     */
    public List<MemoryMessage> getHistory(String sessionId) {
        String key = KEY_PREFIX + sessionId;
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json == null || json.isBlank()) {
                return new ArrayList<>();
            }
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("[Memory] 读取会话记忆失败: sessionId={}", sessionId, e);
            return new ArrayList<>();
        }
    }

    /**
     * 追加用户消息和助手回复到会话记忆
     */
    public void append(String sessionId, String userMessage, String assistantReply) {
        List<MemoryMessage> history = getHistory(sessionId);

        history.add(new MemoryMessage("user", userMessage));
        history.add(new MemoryMessage("assistant", assistantReply));

        trimToWindow(history);
        save(sessionId, history);
    }

    /**
     * 追加单条消息
     */
    public void appendMessage(String sessionId, String role, String content) {
        List<MemoryMessage> history = getHistory(sessionId);
        history.add(new MemoryMessage(role, content));
        trimToWindow(history);
        save(sessionId, history);
    }

    /**
     * 将历史消息转换为 model-service 格式的 messages 列表
     */
    public List<Map<String, String>> toMessageList(String sessionId) {
        List<MemoryMessage> history = getHistory(sessionId);
        return history.stream()
                .map(m -> Map.of("role", m.getRole(), "content", m.getContent()))
                .toList();
    }

    /**
     * 清除会话记忆
     */
    public void clear(String sessionId) {
        redisTemplate.delete(KEY_PREFIX + sessionId);
        log.debug("[Memory] 会话记忆已清除: sessionId={}", sessionId);
    }

    /**
     * 获取会话消息数量
     */
    public int size(String sessionId) {
        return getHistory(sessionId).size();
    }

    private void trimToWindow(List<MemoryMessage> history) {
        while (history.size() > maxMessages) {
            // 保留 system 消息（如果有的话位于首位），移除最早的对话消息
            if (history.size() > 1 && "system".equals(history.get(0).getRole())) {
                history.remove(1);
            } else {
                history.remove(0);
            }
        }
    }

    private void save(String sessionId, List<MemoryMessage> history) {
        String key = KEY_PREFIX + sessionId;
        try {
            String json = objectMapper.writeValueAsString(history);
            redisTemplate.opsForValue().set(key, json, Duration.ofHours(ttlHours));
        } catch (Exception e) {
            log.error("[Memory] 保存会话记忆失败: sessionId={}", sessionId, e);
        }
    }
}
