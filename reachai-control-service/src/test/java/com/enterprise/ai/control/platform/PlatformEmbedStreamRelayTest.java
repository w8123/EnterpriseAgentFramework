package com.enterprise.ai.control.platform;

import com.enterprise.ai.control.runtime.RuntimeTrustedAgentExecutionGateway;
import com.enterprise.ai.control.runtime.RuntimeAgentStreamProxy;
import com.enterprise.ai.control.runtime.SseStreamRelay;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class PlatformEmbedStreamRelayTest {
    private static PlatformEmbedTurnLeaseService.TurnAdmission admission(long epoch) {
        return new PlatformEmbedTurnLeaseService.TurnAdmission(epoch, 1L, 1L);
    }
    private static PlatformEmbedTurnLeaseService leases() {
        return mock(PlatformEmbedTurnLeaseService.class, invocation ->
                invocation.getMethod().getReturnType() == boolean.class ? true
                        : org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation));
    }

    @Test
    void readOnlyUiCardDoesNotMarkTheTurnWaiting() throws Exception {
        var proxy = mock(RuntimeAgentStreamProxy.class);
        var lease = leases();
        var relay = new PlatformEmbedStreamRelay(proxy, mock(RuntimeTrustedAgentExecutionGateway.class),
                mock(PlatformEmbedChatEventService.class), new ObjectMapper());
        var session = new PlatformEmbedSessionEntity(); session.setSessionId("embed-1");
        var body = Map.<String, Object>of("agentId", "bot");
        doAnswer(invocation -> {
            SseStreamRelay.FrameHandler handler = invocation.getArgument(2);
            ByteArrayOutputStream downstream = invocation.getArgument(1);
            handler.handle("ui.requested", "{\"component\":\"table\",\"interactionId\":\"display-1\"}", downstream);
            handler.handle("execution.completed", "{\"answer\":\"ok\",\"metadata\":{\"code\":\"SUPERVISOR_COMPLETED\"}}", downstream);
            return null;
        }).when(proxy).streamAgentExecute(eq(body), any(), any());
        var output = new ByteArrayOutputStream();
        relay.streamMessage(session, body, output, admission(7), lease);
        verify(lease, org.mockito.Mockito.never()).waiting(any(), any(), any());
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("event: ui.requested"));
    }

    @Test
    void bindsRuntimeInteractionToTheAdmittedTurnEpoch() throws Exception {
        var proxy = mock(RuntimeAgentStreamProxy.class);
        var events = mock(PlatformEmbedChatEventService.class);
        var leases = leases();
        var relay = new PlatformEmbedStreamRelay(proxy, mock(RuntimeTrustedAgentExecutionGateway.class), events, new ObjectMapper());
        var session = new PlatformEmbedSessionEntity();
        session.setSessionId("embed-1");
        var body = Map.<String, Object>of("agentId", "bot");
        doAnswer(invocation -> {
            SseStreamRelay.FrameHandler handler = invocation.getArgument(2);
            ByteArrayOutputStream downstream = invocation.getArgument(1);
            handler.handle("execution.completed", "{\"metadata\":{\"code\":\"RUNTIME_GRAPH_INTERACTION_WAITING\"},\"uiRequest\":{\"interactionId\":\"interaction-1\"}}", downstream);
            return null;
        }).when(proxy).streamAgentExecute(eq(body), any(), any());
        relay.streamMessage(session, body, new ByteArrayOutputStream(), admission(42L), leases);
        verify(leases).waiting(session, admission(42L), "interaction-1");
    }

    @Test
    void bindsInteractionBeforeForwardingTheWaitingEvent() throws Exception {
        var proxy = mock(RuntimeAgentStreamProxy.class);
        var events = mock(PlatformEmbedChatEventService.class);
        var leases = leases();
        var relay = new PlatformEmbedStreamRelay(proxy, mock(RuntimeTrustedAgentExecutionGateway.class), events, new ObjectMapper());
        var session = new PlatformEmbedSessionEntity();
        session.setSessionId("embed-1");
        var body = Map.<String, Object>of("agentId", "bot");
        doAnswer(invocation -> {
            SseStreamRelay.FrameHandler handler = invocation.getArgument(2);
            var output = new java.io.OutputStream() {
                @Override public void write(int b) { verify(leases).waiting(session, admission(42L), "interaction-1"); }
            };
            handler.handle("ui.requested", "{\"interactionId\":\"interaction-1\"}", output);
            handler.handle("execution.completed", "{\"metadata\":{\"code\":\"RUNTIME_GRAPH_INTERACTION_WAITING\"},\"uiRequest\":{\"interactionId\":\"interaction-1\"}}", output);
            return null;
        }).when(proxy).streamAgentExecute(eq(body), any(), any());
        relay.streamMessage(session, body, new ByteArrayOutputStream(), admission(42L), leases);
    }

    @Test
    void knownIncompleteAndUnknownWriteErrorsDoNotEncourageBlindRetry() throws Exception {
        var proxy = mock(RuntimeAgentStreamProxy.class);
        var relay = new PlatformEmbedStreamRelay(proxy, mock(RuntimeTrustedAgentExecutionGateway.class),
                mock(PlatformEmbedChatEventService.class), new ObjectMapper());
        var session = new PlatformEmbedSessionEntity();
        session.setSessionId("embed-1");
        var body = Map.<String, Object>of("agentId", "bot");
        doAnswer(invocation -> {
            SseStreamRelay.FrameHandler handler = invocation.getArgument(2);
            ByteArrayOutputStream downstream = invocation.getArgument(1);
            handler.handle("execution.error", "{\"code\":\"SUPERVISOR_PLAN_INCOMPLETE\",\"message\":\"private\"}", downstream);
            handler.handle("execution.error", "{\"code\":\"HTTP_API_WORKFLOW_RESULT_UNCONFIRMED\",\"message\":\"secret\"}", downstream);
            return null;
        }).when(proxy).streamAgentExecute(eq(body), any(), any());
        var output = new ByteArrayOutputStream();
        relay.streamMessage(session, body, output, admission(0L), leases());
        String streamed = output.toString(StandardCharsets.UTF_8);
        assertTrue(streamed.contains("请先核对已执行的操作"));
        assertTrue(streamed.contains("请先核对业务记录，避免重复提交"));
        assertFalse(streamed.contains("private"));
        assertFalse(streamed.contains("secret"));
    }

    @Test
    void mapsRuntimeCompletionToEmbedMessageCompletedAndRecordsAssistantAudit() throws Exception {
        RuntimeAgentStreamProxy streamProxy = mock(RuntimeAgentStreamProxy.class);
        PlatformEmbedChatEventService chatEventService = mock(PlatformEmbedChatEventService.class);
        var leases = leases();
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
                    "{\"sessionId\":\"embed-1\",\"answer\":\"订单已找到\",\"metadata\":{"
                            + "\"traceId\":\"trace-1\","
                            + "\"control.sessionLookupMs\":12,"
                            + "\"control.userMessageAuditMs\":3,"
                            + "\"control.preRuntimeMs\":18"
                            + "}}",
                    downstream);
            return null;
        }).when(streamProxy).streamAgentExecute(eq(runtimeBody), any(), any());

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        relay.streamMessage(session, runtimeBody, output, admission(42L), leases);
        String streamed = output.toString(StandardCharsets.UTF_8);

        assertTrue(streamed.contains("event: message.delta"));
        assertTrue(!streamed.contains("supervisor.step"));
        assertTrue(streamed.contains("event: message.completed"));
        assertTrue(streamed.contains("control.sessionLookupMs"));
        assertTrue(streamed.contains("control.userMessageAuditMs"));
        assertTrue(streamed.contains("control.preRuntimeMs"));
        assertTrue(streamed.contains("control.assistantAuditMs"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> completion = ArgumentCaptor.forClass(Map.class);
        verify(leases, times(1)).completeAndRecord(
                eq(session),
                eq(admission(42L)),
                eq("订单已找到"),
                completion.capture(),
                eq("trace-1"));
        assertEquals("embed-1", completion.getValue().get("sessionId"));
        assertEquals("订单已找到", completion.getValue().get("answer"));
        Map<?, ?> metadata = (Map<?, ?>) completion.getValue().get("metadata");
        assertEquals("trace-1", metadata.get("traceId"));
        assertEquals(12, ((Number) metadata.get("control.sessionLookupMs")).intValue());
        assertEquals(3, ((Number) metadata.get("control.userMessageAuditMs")).intValue());
        assertEquals(18, ((Number) metadata.get("control.preRuntimeMs")).intValue());
        assertTrue(((Number) metadata.get("control.assistantAuditMs")).longValue() >= 0L);
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
            handler.handle("execution.error", "{\"code\":\"FAILED\",\"message\":\"internal detail\",\"answer\":\"internal detail\"}", downstream);
            return null;
        }).when(streamProxy).streamAgentExecute(eq(runtimeBody), any(), any());

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        relay.streamMessage(session, runtimeBody, output, admission(0L), leases());

        String streamed = output.toString(StandardCharsets.UTF_8);
        assertTrue(streamed.contains("event: error"));
        assertTrue(streamed.contains("助手未能完成请求，请先核对业务记录"));
        assertTrue(streamed.contains("FAILED"));
        assertFalse(streamed.contains("internal detail"));
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
        relay.streamMessage(session, runtimeBody, output, admission(0L), leases());
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
            handler.handle("execution.completed", "{\"answer\":\"done\",\"metadata\":{\"code\":\"SUPERVISOR_COMPLETED\"}}", downstream);
            return null;
        }).when(streamProxy).streamAgentExecute(eq(runtimeBody), any(), any());

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        relay.streamMessage(session, runtimeBody, output, admission(0L), leases());
        String streamed = output.toString(StandardCharsets.UTF_8);

        assertFalse(streamed.contains("supervisor.step"));
        assertFalse(streamed.contains("reasoning.delta"));
        assertFalse(streamed.contains("debug.trace"));
        assertTrue(streamed.contains("event: ui.requested"));
    }

    @Test
    void exposesOnlySafeLifecycleProgressForRecognizedSupervisorPhases() throws Exception {
        RuntimeAgentStreamProxy streamProxy = mock(RuntimeAgentStreamProxy.class);
        PlatformEmbedStreamRelay relay = new PlatformEmbedStreamRelay(
                streamProxy,
                mock(RuntimeTrustedAgentExecutionGateway.class),
                mock(PlatformEmbedChatEventService.class),
                new ObjectMapper());
        PlatformEmbedSessionEntity session = new PlatformEmbedSessionEntity();
        session.setSessionId("embed-progress");
        Map<String, Object> runtimeBody = Map.of("agentId", "orders-bot");
        doAnswer(invocation -> {
            SseStreamRelay.FrameHandler handler = invocation.getArgument(2);
            ByteArrayOutputStream downstream = invocation.getArgument(1);
            handler.handle("supervisor.step", "{\"stepId\":\"workflow-1\",\"name\":\"workflow\","
                    + "\"state\":\"started\",\"title\":\"internal workflow name\","
                    + "\"detail\":\"secret tool arguments\"}", downstream);
            return null;
        }).when(streamProxy).streamAgentExecute(eq(runtimeBody), any(), any());

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        relay.streamMessage(session, runtimeBody, output, admission(0L), leases());
        String streamed = output.toString(StandardCharsets.UTF_8);

        assertTrue(streamed.contains("event: turn.progress"));
        assertTrue(streamed.contains("正在调用业务能力"));
        assertFalse(streamed.contains("internal workflow name"));
        assertFalse(streamed.contains("secret tool arguments"));
        assertFalse(streamed.contains("event: supervisor.step"));
    }

    @Test
    void usesSignedInternalStreamWhenEmbedClaimsUserIdPresent() throws Exception {
        RuntimeAgentStreamProxy streamProxy = mock(RuntimeAgentStreamProxy.class);
        RuntimeTrustedAgentExecutionGateway gateway = mock(RuntimeTrustedAgentExecutionGateway.class);
        PlatformEmbedStreamRelay relay = new PlatformEmbedStreamRelay(
                streamProxy, gateway, mock(PlatformEmbedChatEventService.class), new ObjectMapper());
        PlatformEmbedSessionEntity session = new PlatformEmbedSessionEntity();
        session.setSessionId("embed-1");
        session.setProjectCode("erp");
        session.setExternalUserId("user-1");
        Map<String, Object> runtimeBody = Map.of("agentId", "orders-bot", "userId", "attacker");
        doAnswer(invocation -> {
            SseStreamRelay.FrameHandler handler = invocation.getArgument(6);
            ByteArrayOutputStream downstream = invocation.getArgument(5);
            handler.handle("execution.completed",
                    "{\"sessionId\":\"embed-1\",\"answer\":\"ok\",\"metadata\":{}}",
                    downstream);
            return null;
        }).when(gateway).streamTrusted(any(), eq("EMBED_SESSION"), eq("default"), eq("user-1"), any(), any(), any());

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        relay.streamMessage(session, runtimeBody, output, admission(0L), leases());
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("event: message.completed"));
        verify(gateway).streamTrusted(eq(runtimeBody), eq("EMBED_SESSION"), eq("default"), eq("user-1"), any(), any(), any());
    }
}
