package com.enterprise.ai.control.platform;

import com.enterprise.ai.control.client.runtime.RuntimeTrustedAgentExecutionGateway;
import com.enterprise.ai.control.runtime.RuntimeAgentStreamProxy;
import com.enterprise.ai.control.runtime.SseStreamRelay;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PlatformEmbedStreamRelayTest {

    @Test
    void mapsRuntimeCompletionToEmbedMessageCompletedAndRecordsAssistantAudit() throws Exception {
        RuntimeAgentStreamProxy streamProxy = mock(RuntimeAgentStreamProxy.class);
        PlatformEmbedChatEventService chatEventService = mock(PlatformEmbedChatEventService.class);
        PlatformEmbedStreamRelay relay = new PlatformEmbedStreamRelay(
                streamProxy, mock(RuntimeTrustedAgentExecutionGateway.class), chatEventService, new ObjectMapper());
        PlatformEmbedSessionEntity session = new PlatformEmbedSessionEntity();
        session.setSessionId("embed-1");
        session.setAgentId("orders-bot");
        Map<String, Object> runtimeBody = Map.of("agentId", "orders-bot", "sessionId", "embed-1");
        doAnswer(invocation -> {
            SseStreamRelay.FrameHandler handler = invocation.getArgument(2);
            ByteArrayOutputStream downstream = invocation.getArgument(1);
            handler.handle("message.delta", "{\"text\":\"订单\"}", downstream);
            handler.handle("supervisor.step", "{\"step\":\"plan\"}", downstream);
            handler.handle("execution.completed",
                    "{\"sessionId\":\"embed-1\",\"answer\":\"订单已找到\",\"metadata\":{\"traceId\":\"trace-1\"}}",
                    downstream);
            return null;
        }).when(streamProxy).streamAgentExecute(eq(runtimeBody), any(), any());

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        relay.streamMessage(session, runtimeBody, output);
        String streamed = output.toString(StandardCharsets.UTF_8);

        assertTrue(streamed.contains("event: message.delta"));
        assertTrue(!streamed.contains("supervisor.step"));
        assertTrue(streamed.contains("event: message.completed"));
        verify(chatEventService).recordAssistantMessage(
                eq(session),
                eq("订单已找到"),
                eq(Map.of("sessionId", "embed-1", "answer", "订单已找到", "metadata", Map.of("traceId", "trace-1"))),
                eq("trace-1"));
    }

    @Test
    void mapsRuntimeExecutionErrorToEmbedErrorEvent() throws Exception {
        RuntimeAgentStreamProxy streamProxy = mock(RuntimeAgentStreamProxy.class);
        PlatformEmbedStreamRelay relay = new PlatformEmbedStreamRelay(
                streamProxy,
                mock(RuntimeTrustedAgentExecutionGateway.class),
                mock(PlatformEmbedChatEventService.class),
                new ObjectMapper());
        PlatformEmbedSessionEntity session = new PlatformEmbedSessionEntity();
        session.setSessionId("embed-1");
        Map<String, Object> runtimeBody = Map.of("agentId", "orders-bot");
        doAnswer(invocation -> {
            SseStreamRelay.FrameHandler handler = invocation.getArgument(2);
            ByteArrayOutputStream downstream = invocation.getArgument(1);
            handler.handle("execution.error", "{\"code\":\"FAILED\",\"message\":\"boom\"}", downstream);
            return null;
        }).when(streamProxy).streamAgentExecute(eq(runtimeBody), any(), any());

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        relay.streamMessage(session, runtimeBody, output);

        assertTrue(output.toString(StandardCharsets.UTF_8).contains("event: error"));
    }

    @Test
    void forwardsPageActionRequestedWithFullPayload() throws Exception {
        RuntimeAgentStreamProxy streamProxy = mock(RuntimeAgentStreamProxy.class);
        PlatformEmbedStreamRelay relay = new PlatformEmbedStreamRelay(
                streamProxy,
                mock(RuntimeTrustedAgentExecutionGateway.class),
                mock(PlatformEmbedChatEventService.class),
                new ObjectMapper());
        PlatformEmbedSessionEntity session = new PlatformEmbedSessionEntity();
        session.setSessionId("embed-pa");
        Map<String, Object> runtimeBody = Map.of("agentId", "orders-bot", "sessionId", "embed-pa");
        String payload = "{\"type\":\"page.action.requested\",\"protocolVersion\":\"1.0\","
                + "\"requestId\":\"pa-1\",\"actionKey\":\"refresh\",\"title\":\"Refresh\","
                + "\"args\":{\"id\":1},\"target\":{\"pageKey\":\"orders\"},\"confirm\":false,"
                + "\"metadata\":{\"pageKey\":\"orders\"}}";
        doAnswer(invocation -> {
            SseStreamRelay.FrameHandler handler = invocation.getArgument(2);
            ByteArrayOutputStream downstream = invocation.getArgument(1);
            handler.handle("page.action.requested", payload, downstream);
            return null;
        }).when(streamProxy).streamAgentExecute(eq(runtimeBody), any(), any());

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        relay.streamMessage(session, runtimeBody, output);
        String streamed = output.toString(StandardCharsets.UTF_8);

        assertTrue(streamed.contains("event: page.action.requested"));
        assertTrue(streamed.contains("\"requestId\":\"pa-1\""));
        assertFalse(streamed.contains("event: message.completed"));
    }

    @Test
    void stillFiltersSupervisorStepAndReasoningDelta() throws Exception {
        RuntimeAgentStreamProxy streamProxy = mock(RuntimeAgentStreamProxy.class);
        PlatformEmbedStreamRelay relay = new PlatformEmbedStreamRelay(
                streamProxy,
                mock(RuntimeTrustedAgentExecutionGateway.class),
                mock(PlatformEmbedChatEventService.class),
                new ObjectMapper());
        PlatformEmbedSessionEntity session = new PlatformEmbedSessionEntity();
        session.setSessionId("embed-filter");
        Map<String, Object> runtimeBody = Map.of("agentId", "orders-bot");
        doAnswer(invocation -> {
            SseStreamRelay.FrameHandler handler = invocation.getArgument(2);
            ByteArrayOutputStream downstream = invocation.getArgument(1);
            handler.handle("supervisor.step", "{\"step\":\"plan\"}", downstream);
            handler.handle("reasoning.delta", "{\"text\":\"think\"}", downstream);
            handler.handle("debug.trace", "{\"id\":\"t1\"}", downstream);
            handler.handle("ui.requested", "{\"uiRequest\":{\"interactionId\":\"ix-1\"}}", downstream);
            return null;
        }).when(streamProxy).streamAgentExecute(eq(runtimeBody), any(), any());

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        relay.streamMessage(session, runtimeBody, output);
        String streamed = output.toString(StandardCharsets.UTF_8);

        assertFalse(streamed.contains("supervisor.step"));
        assertFalse(streamed.contains("reasoning.delta"));
        assertFalse(streamed.contains("debug.trace"));
        assertTrue(streamed.contains("event: ui.requested"));
    }

    @Test
    void usesSignedInternalStreamWhenEmbedClaimsUserIdPresent() throws Exception {
        RuntimeAgentStreamProxy streamProxy = mock(RuntimeAgentStreamProxy.class);
        RuntimeTrustedAgentExecutionGateway gateway = mock(RuntimeTrustedAgentExecutionGateway.class);
        PlatformEmbedStreamRelay relay = new PlatformEmbedStreamRelay(
                streamProxy, gateway, mock(PlatformEmbedChatEventService.class), new ObjectMapper());
        PlatformEmbedSessionEntity session = new PlatformEmbedSessionEntity();
        session.setSessionId("embed-1");
        session.setExternalUserId("user-1");
        Map<String, Object> runtimeBody = Map.of("agentId", "orders-bot", "userId", "attacker");
        doAnswer(invocation -> {
            SseStreamRelay.FrameHandler handler = invocation.getArgument(4);
            ByteArrayOutputStream downstream = invocation.getArgument(3);
            handler.handle("execution.completed",
                    "{\"sessionId\":\"embed-1\",\"answer\":\"ok\",\"metadata\":{}}",
                    downstream);
            return null;
        }).when(gateway).streamTrusted(any(), eq("EMBED_SESSION"), eq("user-1"), any(), any());

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        relay.streamMessage(session, runtimeBody, output);
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("event: message.completed"));
        verify(gateway).streamTrusted(eq(runtimeBody), eq("EMBED_SESSION"), eq("user-1"), any(), any());
    }
}
