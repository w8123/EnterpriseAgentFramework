package com.enterprise.ai.model.util;

import com.enterprise.ai.model.service.ChatRequest;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;

import java.util.List;
import java.util.Locale;

/**
 * Chat 请求调试日志只记录结构和长度，不记录对话、推理、工具参数或工具定义。
 */
public final class ChatDebugLogs {

    private ChatDebugLogs() {
    }

    public static void logChatRequest(Logger log, String prefix, ChatRequest request) {
        if (!log.isDebugEnabled()) {
            return;
        }
        if (request == null) {
            log.debug("{} request=null", prefix);
            return;
        }
        log.debug("{} modelInstanceId={}, toolCount={}, toolChoiceConfigured={}, optionCount={}",
                prefix,
                request.getModelInstanceId(),
                arraySize(request.getTools()),
                request.getToolChoice() != null && !request.getToolChoice().isNull(),
                request.getOptions() == null ? 0 : request.getOptions().size());

        List<ChatRequest.ChatMessage> messages = request.getMessages();
        if (messages == null) {
            log.debug("{} messages=null", prefix);
            return;
        }
        if (messages.isEmpty()) {
            log.debug("{} messages 为空", prefix);
            return;
        }
        for (int i = 0; i < messages.size(); i++) {
            ChatRequest.ChatMessage m = messages.get(i);
            if (m == null) {
                log.debug("{} message[{}/{}] is null", prefix, i + 1, messages.size());
                continue;
            }
            int contentLen = m.getContent() == null ? 0 : m.getContent().length();
            int reasoningLen = m.getReasoningContent() == null ? 0 : m.getReasoningContent().length();
            log.debug("{} message[{}/{}] role={}, contentLength={}, reasoningLength={}, toolCallCount={}",
                    prefix, i + 1, messages.size(),
                    safeRole(m.getRole()), contentLen, reasoningLen, arraySize(m.getToolCalls()));
        }
    }

    private static int arraySize(JsonNode node) {
        return node != null && node.isArray() ? node.size() : 0;
    }

    private static String safeRole(String role) {
        if (role == null) return "unknown";
        String normalized = role.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "system", "developer", "user", "assistant", "tool" -> normalized;
            default -> "unknown";
        };
    }
}
