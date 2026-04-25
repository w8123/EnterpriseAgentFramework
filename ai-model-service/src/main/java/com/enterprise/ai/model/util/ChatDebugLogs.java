package com.enterprise.ai.model.util;

import com.enterprise.ai.model.service.ChatRequest;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;

import java.util.List;
import java.util.Map;

/**
 * Chat 请求调试日志：在 DEBUG 级别输出 messages 角色、正文预览及 tools 等摘要。
 */
public final class ChatDebugLogs {

    private static final int MAX_CONTENT_PREVIEW = 500;
    private static final int MAX_JSON_PREVIEW = 400;

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
        log.debug("{} provider={}, model={}, tools={}, toolChoice={}, optionsKeys={}",
                prefix,
                request.getProvider(),
                request.getModel(),
                jsonPreview(request.getTools()),
                jsonPreview(request.getToolChoice()),
                optionsKeys(request.getOptions()));

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
            int contentLen = m.getContent() == null ? 0 : m.getContent().length();
            log.debug("{} message[{}/{}] role={}, name={}, toolCallId={}, content长度={}, content预览: {}",
                    prefix, i + 1, messages.size(),
                    m.getRole(), m.getName(), m.getToolCallId(), contentLen,
                    previewText(m.getContent()));
            if (m.getReasoningContent() != null && !m.getReasoningContent().isBlank()) {
                log.debug("{} message[{}/{}] reasoningContent长度={}, 预览: {}",
                        prefix, i + 1, messages.size(), m.getReasoningContent().length(),
                        previewText(m.getReasoningContent()));
            }
            if (m.getToolCalls() != null && !m.getToolCalls().isNull()) {
                log.debug("{} message[{}/{}] tool_calls: {}", prefix, i + 1, messages.size(),
                        jsonPreview(m.getToolCalls()));
            }
        }
    }

    private static String optionsKeys(Map<String, Object> options) {
        if (options == null || options.isEmpty()) {
            return "null";
        }
        return options.keySet().toString();
    }

    private static String jsonPreview(JsonNode n) {
        if (n == null || n.isNull()) {
            return "null";
        }
        String s = n.toString();
        if (s.length() <= MAX_JSON_PREVIEW) {
            return s;
        }
        return s.substring(0, MAX_JSON_PREVIEW) + "... (总长=" + s.length() + ")";
    }

    private static String previewText(String t) {
        if (t == null) {
            return "null";
        }
        String normalized = t.replace("\r\n", "\n").replace('\r', '\n')
                .replace("\n", "\\n");
        if (normalized.length() <= MAX_CONTENT_PREVIEW) {
            return normalized;
        }
        return normalized.substring(0, MAX_CONTENT_PREVIEW)
                + "... (已截断预览，原文长度=" + t.length() + ")";
    }
}
