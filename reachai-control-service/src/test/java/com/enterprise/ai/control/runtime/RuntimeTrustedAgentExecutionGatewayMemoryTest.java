package com.enterprise.ai.control.runtime;

import com.enterprise.ai.control.memory.PersonalMemoryRecallService;
import com.enterprise.ai.control.memory.PersonalMemoryIdentityResolver.PersonalMemoryPrincipal;
import com.enterprise.ai.control.memory.PersonalMemoryConversationService;
import com.enterprise.ai.control.internalauth.InternalServiceAuthSigner;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeTrustedAgentExecutionGatewayMemoryTest {

    @Test
    void embedEnvelopeSignsTheVerifiedSessionRolesBesideTheBusinessBody() throws Exception {
        var mapper = new ObjectMapper();
        var gateway = new RuntimeTrustedAgentExecutionGateway(mock(InternalServiceAuthSigner.class), mapper,
                mock(RuntimeAgentStreamProxy.class), "http://localhost:18604");
        var owner = PersonalMemoryPrincipal.business("tenant-a", "42", "erp-a");
        var body = Map.<String, Object>of("roles", List.of("Customer"), "message", "hello");
        var envelope = mapper.readTree(gateway.serializeEnvelope(body, "EMBED_SESSION", "tenant-a", "42", owner));
        assertEquals("Customer", envelope.path("identity").path("roles").get(0).asText());
        var platform = mapper.readTree(gateway.serializeEnvelope(body, "AGENT", "tenant-a", "42"));
        assertFalse(platform.path("identity").has("roles"));
    }

    @Test
    void scopedClearSignsExactlyTheJsonBytesSentOverHttp() throws Exception {
        var received = new java.util.concurrent.atomic.AtomicReference<byte[]>();
        var signed = new java.util.concurrent.atomic.AtomicReference<byte[]>();
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/runtime/agents/sessions/session-1", exchange -> {
            received.set(exchange.getRequestBody().readAllBytes());
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        try {
            var signer = mock(InternalServiceAuthSigner.class);
            when(signer.sign(eq("DELETE"), eq("/internal/runtime/agents/sessions/session-1"),
                    eq("EMBED_SESSION"), eq("tenant-a"), eq("42"), any(byte[].class)))
                    .thenAnswer(invocation -> {
                        signed.set(invocation.getArgument(5));
                        return Map.of();
                    });
            var mapper = new ObjectMapper();
            var gateway = new RuntimeTrustedAgentExecutionGateway(signer, mapper, mock(RuntimeAgentStreamProxy.class),
                    "http://127.0.0.1:" + server.getAddress().getPort());
            var owner = PersonalMemoryPrincipal.business("tenant-a", "42", "erp-a");
            assertEquals(204, gateway.clearTrusted("session-1", "EMBED_SESSION", "tenant-a", "42", owner)
                    .getStatusCode().value());
            org.junit.jupiter.api.Assertions.assertArrayEquals(signed.get(), received.get());
            assertEquals("erp-a", mapper.readTree(received.get()).get("projectCode").asText());
            assertThrows(IllegalArgumentException.class,
                    () -> gateway.clearTrusted("session-1", "EMBED_SESSION", "tenant-a", "other", owner));
        } finally {
            server.stop(0);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void signsPersonalMemoryBesideBodyInsteadOfMixingItIntoWorkflowInput() throws Exception {
        PersonalMemoryRecallService recall = mock(PersonalMemoryRecallService.class);
        when(recall.recall(eq("AGENT"), eq("default"), eq("42"), any())).thenReturn(Map.of(
                "schema", "reachai-personal-memory-context-v1",
                "memories", List.of(Map.of("id", 1, "type", "FACT", "content", "住在青岛"))));
        ObjectMapper mapper = new ObjectMapper();
        RuntimeTrustedAgentExecutionGateway gateway = new RuntimeTrustedAgentExecutionGateway(
                mock(InternalServiceAuthSigner.class), mapper, mock(RuntimeAgentStreamProxy.class),
                "http://localhost:18604", recall);
        Map<String, Object> publicBody = Map.of("agentId", "a1", "message", "我住哪里");

        Map<String, Object> envelope = mapper.readValue(
                gateway.serializeEnvelope(publicBody, "AGENT", "default", "42"), Map.class);

        assertEquals(publicBody, envelope.get("body"));
        assertTrue(envelope.containsKey("personalMemory"));
        assertFalse(((Map<String, Object>) envelope.get("body")).containsKey("personalMemory"));
        assertEquals("42", ((Map<String, Object>) envelope.get("identity")).get("userId"));
    }

    @Test
    void embedRecallRequiresAnAttestedScopedOwnerMatchingTheSignedUser() throws Exception {
        var recall = mock(PersonalMemoryRecallService.class);
        var mapper = new ObjectMapper();
        var gateway = new RuntimeTrustedAgentExecutionGateway(mock(InternalServiceAuthSigner.class), mapper,
                mock(RuntimeAgentStreamProxy.class), "http://localhost:18604", recall);
        var owner = PersonalMemoryPrincipal.business("tenant-a", "42", "erp-a");
        when(recall.recall(eq(owner), any())).thenReturn(Map.of("schema", "reachai-personal-memory-context-v1"));
        var body = Map.<String, Object>of("message", "hello", "projectCode", "attacker-controlled-value");
        var envelope = mapper.readValue(gateway.serializeEnvelope(body, "EMBED_SESSION", "tenant-a", "42", owner), Map.class);
        assertTrue(envelope.containsKey("personalMemory"));
        assertEquals("erp-a", ((Map<?, ?>) envelope.get("identity")).get("projectCode"));
        verify(recall).recall(eq(owner), eq(body));
        assertThrows(IllegalArgumentException.class, () -> gateway.serializeEnvelope(body, "EMBED_SESSION", "tenant-a", "other", owner));
        assertThrows(IllegalArgumentException.class, () -> gateway.serializeEnvelope(body, "EMBED_SESSION", "tenant-b", "42", owner));
        assertThrows(IllegalArgumentException.class,
                () -> gateway.serializeEnvelope(body, "EMBED_SESSION", "tenant-a", "42"));
    }

    @Test
    void explicitRememberRunsOnlyForARealSuccessfulExecution() throws Exception {
        PersonalMemoryConversationService observation = mock(PersonalMemoryConversationService.class);
        RuntimeTrustedAgentExecutionGateway gateway = new RuntimeTrustedAgentExecutionGateway(
                mock(InternalServiceAuthSigner.class), new ObjectMapper(), mock(RuntimeAgentStreamProxy.class),
                "http://localhost:18604", null, observation);
        Method method = RuntimeTrustedAgentExecutionGateway.class.getDeclaredMethod(
                "observeCompletedTurn", Map.class, Map.class, String.class, String.class, String.class);
        method.setAccessible(true);
        Map<String, Object> request = Map.of("message", "请记住我住在青岛", "agentId", "a1");

        method.invoke(gateway, request, response(false, "EXPLICIT_AND_CANDIDATE"),
                "AGENT", "default", "42");
        verify(observation, never()).rememberExplicitIfPresent(any(), any(), any(), any());

        when(observation.rememberExplicitIfPresent(any(), any(), any(), any())).thenReturn(true);
        method.invoke(gateway, request, response(true, "EXPLICIT_AND_CANDIDATE"),
                "AGENT", "default", "42");
        verify(observation).rememberExplicitIfPresent(eq("AGENT"), eq("default"), eq("42"), any());
    }

    @Test
    void agentMemoryPolicyDecidesWhetherATurnMayWritePersonalMemory() throws Exception {
        PersonalMemoryConversationService observation = mock(PersonalMemoryConversationService.class);
        RuntimeTrustedAgentExecutionGateway gateway = new RuntimeTrustedAgentExecutionGateway(
                mock(InternalServiceAuthSigner.class), new ObjectMapper(), mock(RuntimeAgentStreamProxy.class),
                "http://localhost:18604", null, observation);
        Method method = RuntimeTrustedAgentExecutionGateway.class.getDeclaredMethod(
                "observeCompletedTurn", Map.class, Map.class, String.class, String.class, String.class);
        method.setAccessible(true);
        Map<String, Object> request = Map.of("message", "我喜欢简洁的回答", "agentId", "a1");

        method.invoke(gateway, request, Map.of("success", true, "answer", "好的"), "AGENT", "default", "42");
        method.invoke(gateway, request, response(true, "OFF"), "AGENT", "default", "42");
        verify(observation, never()).rememberExplicitIfPresent(any(), any(), any(), any());

        method.invoke(gateway, request, response(true, "EXPLICIT"), "AGENT", "default", "42");
        verify(observation).rememberExplicitIfPresent(eq("AGENT"), eq("default"), eq("42"), any());

        // Older Agent versions still say EXPLICIT_AND_CANDIDATE; with no candidates left it means EXPLICIT.
        method.invoke(gateway, request, response(true, "EXPLICIT_AND_CANDIDATE"), "AGENT", "default", "42");
        verify(observation, org.mockito.Mockito.times(2))
                .rememberExplicitIfPresent(eq("AGENT"), eq("default"), eq("42"), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void skipsRecallOnceRuntimeReportsTheAgentDoesNotReadPersonalMemory() throws Exception {
        PersonalMemoryRecallService recall = mock(PersonalMemoryRecallService.class);
        when(recall.recall(any(), any(), any(), any())).thenReturn(Map.of(
                "schema", "reachai-personal-memory-context-v1",
                "memories", List.of(Map.of("id", 1, "type", "FACT", "content", "住在青岛"))));
        ObjectMapper mapper = new ObjectMapper();
        RuntimeTrustedAgentExecutionGateway gateway = new RuntimeTrustedAgentExecutionGateway(
                mock(InternalServiceAuthSigner.class), mapper, mock(RuntimeAgentStreamProxy.class),
                "http://localhost:18604", recall, mock(PersonalMemoryConversationService.class));
        Method method = RuntimeTrustedAgentExecutionGateway.class.getDeclaredMethod(
                "observeCompletedTurn", Map.class, Map.class, String.class, String.class, String.class);
        method.setAccessible(true);
        Map<String, Object> request = Map.of("agentId", "a1", "message", "我住哪里");

        method.invoke(gateway, request, Map.of("success", true, "metadata", Map.of(
                "agentId", "a1",
                "memoryPolicy", Map.of("personalMemoryRead", false, "personalMemoryWrite", "OFF"))),
                "AGENT", "default", "42");
        Map<String, Object> envelope = mapper.readValue(
                gateway.serializeEnvelope(request, "AGENT", "default", "42"), Map.class);

        assertFalse(envelope.containsKey("personalMemory"));
        verify(recall, never()).recall(any(), any(), any(), any());
    }

    private static Map<String, Object> response(boolean success, String writeMode) {
        return Map.of("success", success, "answer", success ? "记住了" : "failed",
                "metadata", Map.of("agentId", "a1", "memoryPolicy", Map.of(
                        "personalMemoryRead", true, "personalMemoryWrite", writeMode)));
    }
}
