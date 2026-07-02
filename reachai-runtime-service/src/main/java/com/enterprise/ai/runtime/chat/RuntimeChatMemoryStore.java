package com.enterprise.ai.runtime.chat;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class RuntimeChatMemoryStore {

    private final ConcurrentMap<String, List<RuntimeChatMemoryMessage>> sessions = new ConcurrentHashMap<>();
    private final int maxMessages;

    public RuntimeChatMemoryStore(@Value("${memory.max-messages:20}") int maxMessages) {
        this.maxMessages = Math.max(2, maxMessages);
    }

    public List<RuntimeChatMemoryMessage> getHistory(String sessionId) {
        List<RuntimeChatMemoryMessage> history = sessions.get(sessionId);
        if (history == null || history.isEmpty()) {
            return new ArrayList<>();
        }
        synchronized (history) {
            return new ArrayList<>(history);
        }
    }

    public void append(String sessionId, String userMessage, String assistantReply) {
        List<RuntimeChatMemoryMessage> history =
                sessions.computeIfAbsent(sessionId, ignored -> Collections.synchronizedList(new ArrayList<>()));
        synchronized (history) {
            history.add(new RuntimeChatMemoryMessage("user", userMessage));
            history.add(new RuntimeChatMemoryMessage("assistant", assistantReply));
            trimToWindow(history);
        }
    }

    public void clear(String sessionId) {
        sessions.remove(sessionId);
    }

    private void trimToWindow(List<RuntimeChatMemoryMessage> history) {
        while (history.size() > maxMessages) {
            if (history.size() > 1 && "system".equals(history.get(0).getRole())) {
                history.remove(1);
            } else {
                history.remove(0);
            }
        }
    }
}
