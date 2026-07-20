package com.enterprise.ai.control.platform;

import com.enterprise.ai.control.client.runtime.RuntimeTrustedAgentExecutionGateway;
import com.enterprise.ai.control.runtime.RuntimeAgentStreamProxy;
import com.enterprise.ai.control.runtime.SseStreamRelay;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Proxies Runtime Agent SSE to Embed clients with event mapping and completion audit. */
@Service
@RequiredArgsConstructor
public class PlatformEmbedStreamRelay {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final RuntimeAgentStreamProxy runtimeStreamProxy;
    private final RuntimeTrustedAgentExecutionGateway trustedExecutionGateway;
    private final PlatformEmbedChatEventService chatEventService;
    private final ObjectMapper objectMapper;

    public void streamMessage(PlatformEmbedSessionEntity session,
                              Map<String, Object> runtimeBody,
                              OutputStream outputStream) throws IOException {
        String trustedUserId = firstText(session.getExternalUserId(), session.getGlobalUserId());
        if (StringUtils.hasText(trustedUserId) && trustedExecutionGateway != null) {
            trustedExecutionGateway.streamTrusted(
                    runtimeBody,
                    "EMBED_SESSION",
                    trustedUserId.trim(),
                    outputStream,
                    (eventName, data, downstream) -> relayRuntimeEvent(session, eventName, data, downstream));
            return;
        }
        // Fail closed without verified Embed claims userId: public stream stays userTrusted=false.
        runtimeStreamProxy.streamAgentExecute(runtimeBody, outputStream, (eventName, data, downstream) -> {
            relayRuntimeEvent(session, eventName, data, downstream);
        });
    }

    private void relayRuntimeEvent(PlatformEmbedSessionEntity session,
                                   String eventName,
                                   String data,
                                   OutputStream downstream) throws IOException {
        String normalized = eventName == null ? "" : eventName.trim();
        if ("supervisor.step".equals(normalized)) {
            return;
        }
        if ("execution.completed".equals(normalized)) {
            Map<String, Object> payload = parseJsonMap(data);
            recordCompletion(session, payload);
            SseStreamRelay.writeFrame(downstream, "message.completed", toJson(buildEmbedResponse(session, payload)));
            return;
        }
        if ("execution.error".equals(normalized)) {
            SseStreamRelay.writeFrame(downstream, "error", data);
            return;
        }
        // Embed 公共协议允许的实时事件（不含 supervisor / reasoning / debug）
        if ("message.delta".equals(normalized)
                || "ui.requested".equals(normalized)
                || "page.action.requested".equals(normalized)) {
            SseStreamRelay.writeFrame(downstream, normalized, data);
        }
    }

    private void recordCompletion(PlatformEmbedSessionEntity session, Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return;
        }
        Map<String, Object> metadata = mapValue(payload.get("metadata"));
        String answer = text(payload.get("answer"));
        chatEventService.recordAssistantMessage(session, answer, payload, text(metadata.get("traceId")));
    }

    private PlatformEmbedPublicController.EmbedChatMessageResponse buildEmbedResponse(
            PlatformEmbedSessionEntity session,
            Map<String, Object> payload) {
        Map<String, Object> metadata = mapValue(payload.get("metadata"));
        return new PlatformEmbedPublicController.EmbedChatMessageResponse(
                firstText(text(payload.get("sessionId")), session.getSessionId()),
                text(payload.get("answer")),
                text(payload.get("intentType")),
                stringList(payload.get("toolCalls")),
                metadata,
                payload.get("uiRequest"));
    }

    private Map<String, Object> parseJsonMap(String data) {
        if (!StringUtils.hasText(data)) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(data, MAP_TYPE);
            return parsed == null ? Map.of() : parsed;
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return "{}";
        }
    }

    private String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return StringUtils.hasText(text) ? text.trim() : null;
    }

    private Map<String, Object> mapValue(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, val) -> result.put(String.valueOf(key), val));
        return result;
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof Iterable<?> iterable)) {
            return List.of();
        }
        return java.util.stream.StreamSupport.stream(iterable.spliterator(), false)
                .map(String::valueOf)
                .filter(StringUtils::hasText)
                .toList();
    }

    private String firstText(String primary, String fallback) {
        if (StringUtils.hasText(primary)) {
            return primary.trim();
        }
        return StringUtils.hasText(fallback) ? fallback.trim() : null;
    }
}
