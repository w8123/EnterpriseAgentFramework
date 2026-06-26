package com.enterprise.ai.agent.platform.control.identity;

import com.enterprise.ai.agent.model.ChatResponse;
import com.enterprise.ai.agent.model.interactive.UiRequestPayload;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EmbedChatStreamEventFactoryTest {

    @Test
    void emitsDeltaUiPageActionToolAndCompletedEventsInProtocolOrder() {
        Map<String, Object> pageAction = Map.of(
                "type", "page.action.requested",
                "actionKey", "team.openDetail",
                "target", Map.of("pageInstanceId", "page-1"));
        UiRequestPayload uiRequest = UiRequestPayload.builder()
                .extension(Map.of(
                        "eventType", "page.action.requested",
                        "pageActionRequest", pageAction))
                .build();
        ChatResponse response = ChatResponse.builder()
                .sessionId("embed-1")
                .answer("好的")
                .toolCalls(List.of("team.search"))
                .uiRequest(uiRequest)
                .build();

        List<EmbedChatStreamEvent> events = EmbedChatStreamEventFactory.from(response);

        assertEquals(List.of("message.delta", "page.action.requested", "tool.completed", "message.completed"),
                events.stream().map(EmbedChatStreamEvent::type).toList());
        assertEquals("好的", ((Map<?, ?>) events.get(0).data()).get("text"));
        assertEquals("1.0", ((Map<?, ?>) events.get(1).data()).get("protocolVersion"));
        assertEquals("team.openDetail", ((Map<?, ?>) events.get(1).data()).get("actionKey"));
        assertEquals("team.search", ((Map<?, ?>) events.get(2).data()).get("tool"));
        assertEquals(response, events.get(3).data());
    }
}
