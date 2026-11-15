package com.enterprise.ai.control.platform;

import com.enterprise.ai.control.runtime.RuntimeTrustedAgentExecutionGateway;
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
                              OutputStream outputStream,
                              PlatformEmbedTurnLeaseService.TurnAdmission admission,
                              PlatformEmbedTurnLeaseService leases) throws IOException {
        java.util.concurrent.atomic.AtomicReference<String> pendingUi = new java.util.concurrent.atomic.AtomicReference<>();
        try {
        String trustedUserId = firstText(session.getExternalUserId(), session.getGlobalUserId());
        if (StringUtils.hasText(trustedUserId) && trustedExecutionGateway != null) {
            trustedExecutionGateway.streamTrusted(
                    runtimeBody,
                    "EMBED_SESSION",
                    firstText(session.getTenantId(), "default"),
                    trustedUserId.trim(),
                    com.enterprise.ai.control.memory.PersonalMemoryIdentityResolver.PersonalMemoryPrincipal.business(
                            session.getTenantId(), trustedUserId, session.getProjectCode()),
                    outputStream,
                    (eventName, data, downstream) -> relayRuntimeEvent(session, eventName, data, downstream, admission, leases, pendingUi));
            return;
        }
        // Fail closed without verified Embed claims userId: public stream stays userTrusted=false.
        runtimeStreamProxy.streamAgentExecute(runtimeBody, outputStream, (eventName, data, downstream) -> {
            relayRuntimeEvent(session, eventName, data, downstream, admission, leases, pendingUi);
        });
        } finally {
            // No-op after a committed completion; an interrupted or ambiguous run remains non-idle.
            leases.unknown(session, admission);
        }
    }

    private void relayRuntimeEvent(PlatformEmbedSessionEntity session,
                                   String eventName,
                                   String data,
                                   OutputStream downstream,
                                   PlatformEmbedTurnLeaseService.TurnAdmission admission,
                                   PlatformEmbedTurnLeaseService leases,
                                   java.util.concurrent.atomic.AtomicReference<String> pendingUi) throws IOException {
        String normalized = eventName == null ? "" : eventName.trim();
        if ("supervisor.step".equals(normalized)) {
            Map<String, Object> progress = publicProgress(parseJsonMap(data));
            if (!progress.isEmpty()) {
                SseStreamRelay.writeFrame(downstream, "turn.progress", toJson(progress));
            }
            return;
        }
        if ("execution.completed".equals(normalized)) {
            Map<String, Object> payload = parseJsonMap(data);
            if (!recordCompletion(session, payload, admission, leases)) {
                throw new IOException("Embed turn changed before completion");
            }
            String bufferedUi = pendingUi.getAndSet(null);
            if (bufferedUi != null) SseStreamRelay.writeFrame(downstream, "ui.requested", bufferedUi);
            SseStreamRelay.writeFrame(downstream, "message.completed", toJson(buildEmbedResponse(session, payload)));
            return;
        }
        if ("execution.error".equals(normalized)) {
            Map<String, Object> payload = parseJsonMap(data);
            String code = text(payload.get("code"));
            if (code == null || !code.matches("[A-Z0-9_]{1,80}")) {
                code = "AGENT_EXECUTION_FAILED";
            }
            SseStreamRelay.writeFrame(downstream, "error", toJson(Map.of(
                    "code", code,
                    "message", PlatformEmbedPublicController.safeRuntimeFailureMessage(code))));
            return;
        }
        // Wait for the authoritative terminal frame before exposing a card:
        // read-only cards are not necessarily blocking interactions.
        if ("ui.requested".equals(normalized)) {
            pendingUi.set(data);
            return;
        }
        // Embed 公共协议允许的实时事件（不含 supervisor / reasoning / debug）
        if ("message.delta".equals(normalized)
                || "page.action.requested".equals(normalized)) {
            SseStreamRelay.writeFrame(downstream, normalized, data);
        }
    }

    /**
     * Embed clients may see coarse, user-safe lifecycle progress, but never Supervisor reasoning,
     * plan text, workflow names, tool arguments, or raw phase detail.
     */
    private Map<String, Object> publicProgress(Map<String, Object> phase) {
        String name = text(phase.get("name"));
        String state = firstText(text(phase.get("state")), "started");
        String message = switch (name == null ? "" : name) {
            case "plan", "replan" -> "completed".equals(state)
                    ? "已确定处理步骤"
                    : "正在规划处理步骤";
            case "workflow" -> switch (state) {
                case "completed" -> "业务操作已完成，正在整理结果";
                case "waiting" -> "正在等待必要的页面交互";
                case "failed" -> "业务操作未完成";
                case "cancelled" -> "操作已取消";
                default -> "正在调用业务能力";
            };
            case "capability_search" -> "completed".equals(state)
                    ? "已找到可用的业务能力"
                    : "failed".equals(state) ? "没有找到可用的业务能力" : "正在查找可用的业务能力";
            case "capability" -> switch (state) {
                case "completed" -> "业务能力调用完成，正在整理结果";
                case "failed" -> "业务能力调用未完成";
                default -> "正在调用业务能力";
            };
            case "policy" -> "waiting".equals(state)
                    ? "正在等待必要的安全确认"
                    : "正在执行安全校验";
            case "final_answer" -> "completed".equals(state)
                    ? "已生成最终回答"
                    : "正在整理最终回答";
            default -> null;
        };
        if (!StringUtils.hasText(message)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("phase", name);
        result.put("state", state);
        result.put("message", message);
        return result;
    }

    private boolean recordCompletion(PlatformEmbedSessionEntity session, Map<String, Object> payload,
                                     PlatformEmbedTurnLeaseService.TurnAdmission admission,
                                     PlatformEmbedTurnLeaseService leases) {
        if (payload == null || payload.isEmpty()) {
            return false;
        }
        Map<String, Object> metadata = mapValue(payload.get("metadata"));
        String answer = text(payload.get("answer"));
        long auditStart = System.nanoTime();
        Map<String, Object> uiRequest = mapValue(payload.get("uiRequest"));
        String code = text(metadata.get("code"));
        boolean waiting = "RUNTIME_GRAPH_INTERACTION_WAITING".equals(code)
                || "SUPERVISOR_CONFIRMATION_REQUIRED".equals(code);
        boolean recorded;
        if (waiting) {
            String interactionId = text(uiRequest.get("interactionId"));
            recorded = interactionId != null && leases.waiting(session, admission, interactionId)
                    && leases.recordWaitingAnswer(session, admission, answer, payload, text(metadata.get("traceId")));
        } else {
            recorded = leases.completeAndRecord(session, admission, answer, payload, text(metadata.get("traceId")));
        }
        if (!recorded) return false;
        long assistantAuditMs = Math.max(0L, (System.nanoTime() - auditStart) / 1_000_000L);
        // Observability only — never mutate the already-persisted assistant content.
        if (payload.get("metadata") instanceof Map<?, ?> raw) {
            @SuppressWarnings("unchecked")
            Map<String, Object> mutable = (Map<String, Object>) raw;
            mutable.put("control.assistantAuditMs", assistantAuditMs);
        }
        return true;
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
