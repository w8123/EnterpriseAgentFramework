package com.enterprise.ai.runtime.chat;

public interface RuntimeLightweightChatService {

    RuntimeChatResponse chat(RuntimeChatRequest request);

    void clearSession(String sessionId);
}
