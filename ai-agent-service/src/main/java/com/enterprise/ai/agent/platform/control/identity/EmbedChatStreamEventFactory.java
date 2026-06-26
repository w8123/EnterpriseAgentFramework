package com.enterprise.ai.agent.platform.control.identity;

import com.enterprise.ai.agent.model.ChatResponse;
import com.enterprise.ai.agent.model.interactive.UiRequestPayload;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class EmbedChatStreamEventFactory {

    private EmbedChatStreamEventFactory() {
    }

    public static List<EmbedChatStreamEvent> from(ChatResponse response) {
        List<EmbedChatStreamEvent> events = new ArrayList<>();
        if (response == null) {
            return events;
        }
        if (StringUtils.hasText(response.getAnswer())) {
            events.add(new EmbedChatStreamEvent("message.delta", Map.of(
                    "protocolVersion", "1.0",
                    "text", response.getAnswer())));
        }
        UiRequestPayload uiRequest = response.getUiRequest();
        if (uiRequest != null) {
            Object eventType = uiRequest.getExtension() == null ? null : uiRequest.getExtension().get("eventType");
            Object pageAction = uiRequest.getExtension() == null ? null : uiRequest.getExtension().get("pageActionRequest");
            if ("page.action.requested".equals(eventType) && pageAction != null) {
                events.add(new EmbedChatStreamEvent("page.action.requested", withProtocolVersion(pageAction)));
            } else {
                events.add(new EmbedChatStreamEvent("ui.requested", uiRequest));
            }
        }
        if (response.getToolCalls() != null) {
            for (String tool : response.getToolCalls()) {
                if (StringUtils.hasText(tool)) {
                    events.add(new EmbedChatStreamEvent("tool.completed", Map.of(
                            "protocolVersion", "1.0",
                            "tool", tool)));
                }
            }
        }
        events.add(new EmbedChatStreamEvent("message.completed", response));
        return events;
    }

    private static Object withProtocolVersion(Object event) {
        if (event instanceof Map<?, ?> map && !map.containsKey("protocolVersion")) {
            Map<String, Object> copy = new java.util.LinkedHashMap<>();
            copy.put("protocolVersion", "1.0");
            map.forEach((key, value) -> copy.put(String.valueOf(key), value));
            return copy;
        }
        return event;
    }
}
