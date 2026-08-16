package com.enterprise.ai.runtime.supervisor;

import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatData;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatResult;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelUsage;
import com.enterprise.ai.runtime.memory.RuntimeSessionMemoryKey;
import com.enterprise.ai.runtime.memory.RuntimeSessionMemoryProperties;
import com.enterprise.ai.runtime.memory.RuntimeSessionMemoryService;
import com.enterprise.ai.runtime.memory.RuntimeSessionBusyException;
import com.enterprise.ai.runtime.memory.RuntimeToolResultArtifactService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RuntimeContextEngineeringServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void overflowCompactsPersistsAndResumesWithoutRepeatingCompletedWorkflowSideEffect()
            throws Exception {
        AtomicInteger workflowExecutions = new AtomicInteger();
        AtomicInteger compactionCalls = new AtomicInteger();
        AtomicInteger overflowResponses = new AtomicInteger();
        RuntimeModelServiceClient modelClient = request -> {
            String conversation = request.getMessages().stream()
                    .map(message -> message.getContent() == null ? "" : message.getContent())
                    .reduce("", (left, right) -> left + "\n" + right);
            if (request.getTools() == null) {
                compactionCalls.incrementAndGet();
                return successText("INTENT: finish the order update\n"
                        + "COMPLETED ACTIONS: workflow_order_update completed exactly once\n"
                        + "PENDING: return the verified result");
            }
            if (conversation.contains("Here is a summary of the conversation to date")) {
                return successText("订单更新已完成");
            }
            if (conversation.contains("workflow-result-complete")) {
                overflowResponses.incrementAndGet();
                return new ModelChatResult(
                        400,
                        "context_length_exceeded: maximum context length reached",
                        null);
            }
            return successToolCall("call-workflow-1", "workflow_order_update");
        };

        InMemoryAgentStateStore stateStore = new InMemoryAgentStateStore();
        RuntimeSessionMemoryService sessionMemoryService = new RuntimeSessionMemoryService(
                stateStore,
                null,
                null,
                new RuntimeSessionMemoryProperties(
                        true, "in-memory", "", "", "", false, 40, 65_535, 600));
        RuntimeContextEngineeringProperties properties = new RuntimeContextEngineeringProperties(
                true,
                false,
                true,
                24,
                24_000,
                10,
                2,
                60_000,
                false,
                80_000,
                2_000,
                16_777_216,
                12_000,
                24,
                200,
                60_000,
                "test-key",
                "");
        @SuppressWarnings("unchecked")
        ObjectProvider<RuntimeToolResultArtifactService> artifactProvider =
                mock(ObjectProvider.class);
        when(artifactProvider.getIfAvailable()).thenReturn(null);
        RuntimeContextEngineeringService service = new RuntimeContextEngineeringService(
                properties,
                modelClient,
                null,
                objectMapper,
                sessionMemoryService,
                artifactProvider);
        RuntimeSessionMemoryKey memoryKey = new RuntimeSessionMemoryKey(
                true,
                "tenant-a",
                "user-a",
                "agent-a",
                "public-session",
                "u:owner-hash",
                "a:agent-hash:s:session-hash",
                "AGENT");
        RuntimeContextEngineeringService.Invocation invocation =
                service.begin("model-a", memoryKey, "trace-a");

        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(new AgentTool() {
            @Override
            public String getName() {
                return "workflow_order_update";
            }

            @Override
            public String getDescription() {
                return "Execute the enterprise order update Workflow exactly once.";
            }

            @Override
            public Map<String, Object> getParameters() {
                return Map.of("type", "object", "additionalProperties", false);
            }

            @Override
            public Mono<ToolResultBlock> callAsync(ToolCallParam parameter) {
                workflowExecutions.incrementAndGet();
                return Mono.just(ToolResultBlock.text("workflow-result-complete"));
            }
        });
        ReachAiAgentScopeChatModel publicModel = new ReachAiAgentScopeChatModel(
                "model-a", modelClient, null, objectMapper, null);
        ReActAgent.Builder builder = ReActAgent.builder()
                .name("orders-agent")
                .sysPrompt("Use the Workflow, then report its result.")
                .model(publicModel)
                .toolkit(toolkit)
                .maxIters(8)
                .stateStore(stateStore);
        invocation.configure(toolkit, builder);
        RuntimeContext context = RuntimeContext.builder()
                .userId(memoryKey.stateUserKey())
                .sessionId(memoryKey.stateSessionKey())
                .build();

        try (ReActAgent agent = builder.build()) {
            Msg result = invocation.call(
                            agent,
                            List.of(Msg.builder()
                                    .name("user")
                                    .role(MsgRole.USER)
                                    .textContent("更新订单并告诉我结果")
                                    .build()),
                            context,
                            publicModel)
                    .block(Duration.ofSeconds(20));

            assertEquals("订单更新已完成", result == null ? null : result.getTextContent());
        }

        assertEquals(1, workflowExecutions.get(),
                "completed Workflow side effects must not be repeated after overflow recovery");
        assertEquals(1, compactionCalls.get());
        assertTrue(overflowResponses.get() >= 1);
        assertEquals(1, invocation.safeMetadata().get("contextOverflowRecoveryCount"));
        AgentState persisted = stateStore.get(
                        memoryKey.stateUserKey(),
                        memoryKey.stateSessionKey(),
                        RuntimeSessionMemoryService.STATE_NAME,
                        AgentState.class)
                .orElseThrow();
        long workflowResults = persisted.getContext().stream()
                .filter(message -> message.getRole() == MsgRole.TOOL)
                .count();
        assertEquals(1, workflowResults);
    }

    @Test
    void proactiveCompactionDoesNotRetryADownstreamModelFailure() {
        AtomicInteger compactionCalls = new AtomicInteger();
        AtomicInteger publicModelCalls = new AtomicInteger();
        RuntimeModelServiceClient modelClient = request -> {
            if (request.getTools() == null) {
                compactionCalls.incrementAndGet();
                return successText("INTENT: retain the request\nPENDING: answer the current turn");
            }
            publicModelCalls.incrementAndGet();
            return new ModelChatResult(503, "provider request failed", null);
        };

        InMemoryAgentStateStore stateStore = new InMemoryAgentStateStore();
        RuntimeSessionMemoryService sessionMemoryService = sessionMemoryService(stateStore);
        RuntimeContextEngineeringProperties properties = properties(true, false, false, "");
        @SuppressWarnings("unchecked")
        ObjectProvider<RuntimeToolResultArtifactService> artifactProvider = mock(ObjectProvider.class);
        when(artifactProvider.getIfAvailable()).thenReturn(null);
        RuntimeContextEngineeringService service = new RuntimeContextEngineeringService(
                properties, modelClient, null, objectMapper, sessionMemoryService, artifactProvider);
        RuntimeSessionMemoryKey memoryKey = memoryKey();
        List<Msg> history = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            history.add(message(MsgRole.USER, "historical request " + index));
            history.add(message(MsgRole.ASSISTANT, "historical answer " + index));
        }
        stateStore.save(
                memoryKey.stateUserKey(),
                memoryKey.stateSessionKey(),
                RuntimeSessionMemoryService.STATE_NAME,
                AgentState.builder()
                        .userId(memoryKey.stateUserKey())
                        .sessionId(memoryKey.stateSessionKey())
                        .context(history)
                        .build());

        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(noopTool("available_read_tool"));
        ReachAiAgentScopeChatModel publicModel = new ReachAiAgentScopeChatModel(
                "model-a", modelClient, null, objectMapper, null);
        ReActAgent.Builder builder = ReActAgent.builder()
                .name("orders-agent")
                .sysPrompt("Answer with the available evidence.")
                .model(publicModel)
                .toolkit(toolkit)
                .maxIters(4)
                .stateStore(stateStore);
        RuntimeContextEngineeringService.Invocation invocation =
                service.begin("model-a", memoryKey, "trace-a");
        invocation.configure(toolkit, builder);
        RuntimeContext context = runtimeContext(memoryKey);

        try (ReActAgent agent = builder.build()) {
            assertThrows(RuntimeException.class, () -> invocation.call(
                            agent,
                            List.of(message(MsgRole.USER, "current request")),
                            context,
                            publicModel)
                    .block(Duration.ofSeconds(20)));
        }

        assertEquals(1, compactionCalls.get());
        assertEquals(1, publicModelCalls.get(),
                "a downstream model failure must not be mistaken for a compaction failure and retried");
        AgentState compactedState = stateStore.get(
                        memoryKey.stateUserKey(),
                        memoryKey.stateSessionKey(),
                        RuntimeSessionMemoryService.STATE_NAME,
                        AgentState.class)
                .orElseThrow();
        assertEquals(3, compactedState.getContext().size(),
                "proactive compaction must be durable even when the downstream model call fails");
        assertEquals(MsgRole.ASSISTANT, compactedState.getContext().get(0).getRole(),
                "a generated digest must not be reintroduced with fresh USER authority");
        assertTrue(compactedState.getContext().get(0).getTextContent()
                .contains("<compacted_history_data>"));
    }

    @Test
    void oversizedToolResultIsPersistedBeforeADownstreamModelFailure() {
        String middleMarker = "UNIQUE-MIDDLE-CONTENT-MUST-BE-OFFLOADED";
        String retainedNonTextMarker = "NON-TEXT-BLOCK-MUST-BE-RETAINED";
        String largeResult = "x".repeat(2_500) + middleMarker + "y".repeat(2_500);
        String artifactRef = "tra_0123456789abcdef0123456789abcdef";
        AtomicInteger publicModelCalls = new AtomicInteger();
        AtomicReference<String> secondModelConversation = new AtomicReference<>();
        RuntimeModelServiceClient modelClient = request -> {
            int call = publicModelCalls.incrementAndGet();
            if (call == 1) {
                return successToolCall("call-large-result", "large_result_tool");
            }
            String conversation = request.getMessages().stream()
                    .map(message -> message.getContent() == null ? "" : message.getContent())
                    .reduce("", (left, right) -> left + "\n" + right);
            secondModelConversation.set(conversation);
            return new ModelChatResult(503, "provider request failed", null);
        };

        RuntimeToolResultArtifactService artifactService = mock(RuntimeToolResultArtifactService.class);
        when(artifactService.offload(any(), any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(new RuntimeToolResultArtifactService.ArtifactPointer(
                        artifactRef, largeResult.length(), LocalDateTime.now().plusHours(1))));
        @SuppressWarnings("unchecked")
        ObjectProvider<RuntimeToolResultArtifactService> artifactProvider = mock(ObjectProvider.class);
        when(artifactProvider.getIfAvailable()).thenReturn(artifactService);
        InMemoryAgentStateStore stateStore = new InMemoryAgentStateStore();
        RuntimeSessionMemoryService sessionMemoryService = sessionMemoryService(stateStore);
        RuntimeContextEngineeringService service = new RuntimeContextEngineeringService(
                properties(false, false, true, "0123456789abcdef0123456789abcdef"),
                modelClient,
                null,
                objectMapper,
                sessionMemoryService,
                artifactProvider);
        RuntimeSessionMemoryKey memoryKey = memoryKey();
        RuntimeContextEngineeringService.Invocation invocation =
                service.begin("model-a", memoryKey, "trace-a");
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(new AgentTool() {
            @Override
            public String getName() {
                return "large_result_tool";
            }

            @Override
            public String getDescription() {
                return "Return an oversized read-only result.";
            }

            @Override
            public Map<String, Object> getParameters() {
                return Map.of("type", "object", "additionalProperties", false);
            }

            @Override
            public Mono<ToolResultBlock> callAsync(ToolCallParam parameter) {
                return Mono.just(ToolResultBlock.of(List.of(
                        TextBlock.builder().text(largeResult).build(),
                        ThinkingBlock.builder().thinking(retainedNonTextMarker).build())));
            }
        });
        ReachAiAgentScopeChatModel publicModel = new ReachAiAgentScopeChatModel(
                "model-a", modelClient, null, objectMapper, null);
        ReActAgent.Builder builder = ReActAgent.builder()
                .name("orders-agent")
                .sysPrompt("Use the tool, then answer.")
                .model(publicModel)
                .toolkit(toolkit)
                .maxIters(4)
                .stateStore(stateStore);
        invocation.configure(toolkit, builder);

        try (ReActAgent agent = builder.build()) {
            assertThrows(RuntimeException.class, () -> invocation.call(
                            agent,
                            List.of(message(MsgRole.USER, "fetch the large result")),
                            runtimeContext(memoryKey),
                            publicModel)
                    .block(Duration.ofSeconds(20)));
        }

        assertEquals(2, publicModelCalls.get());
        assertTrue(secondModelConversation.get().contains("Tool output was too large"));
        assertTrue(secondModelConversation.get().contains(artifactRef));
        assertFalse(secondModelConversation.get().contains(middleMarker));
        verify(artifactService).offload(any(), any(), any(), any(), any(), any());
        AgentState persisted = stateStore.get(
                        memoryKey.stateUserKey(),
                        memoryKey.stateSessionKey(),
                        RuntimeSessionMemoryService.STATE_NAME,
                        AgentState.class)
                .orElseThrow();
        assertTrue(toolResultText(persisted).contains(artifactRef));
        assertFalse(toolResultText(persisted).contains(middleMarker));
        assertTrue(persisted.getContext().stream()
                .flatMap(message -> message.getContent().stream())
                .filter(ToolResultBlock.class::isInstance)
                .map(ToolResultBlock.class::cast)
                .flatMap(result -> result.getOutput().stream())
                .filter(ThinkingBlock.class::isInstance)
                .map(ThinkingBlock.class::cast)
                .anyMatch(block -> retainedNonTextMarker.equals(block.getThinking())));
    }

    @Test
    void lostTurnLeasePreventsOversizedToolArtifactFromBeingRecreatedAfterErase() {
        String marker = "LOST-LEASE-CONTENT-MUST-NOT-BE-OFFLOADED";
        String largeResult = "x".repeat(2_500) + marker + "y".repeat(2_500);
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicReference<String> secondModelConversation = new AtomicReference<>();
        RuntimeModelServiceClient modelClient = request -> {
            if (modelCalls.incrementAndGet() == 1) {
                return successToolCall("call-lost-lease", "large_result_tool");
            }
            secondModelConversation.set(request.getMessages().stream()
                    .map(message -> message.getContent() == null ? "" : message.getContent())
                    .reduce("", (left, right) -> left + "\n" + right));
            return successText("lease loss handled safely");
        };

        RuntimeToolResultArtifactService artifactService = mock(RuntimeToolResultArtifactService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<RuntimeToolResultArtifactService> artifactProvider = mock(ObjectProvider.class);
        when(artifactProvider.getIfAvailable()).thenReturn(artifactService);
        RuntimeSessionMemoryService sessionMemoryService = mock(RuntimeSessionMemoryService.class);
        InMemoryAgentStateStore stateStore = new InMemoryAgentStateStore();
        RuntimeSessionMemoryKey memoryKey = memoryKey();
        when(sessionMemoryService.stateStoreForTurn(memoryKey, "lost-owner"))
                .thenReturn(stateStore);
        doThrow(new RuntimeSessionBusyException("turn lease was lost"))
                .when(sessionMemoryService).renewTurnLease(memoryKey, "lost-owner");
        RuntimeContextEngineeringService service = new RuntimeContextEngineeringService(
                properties(false, false, true, "0123456789abcdef0123456789abcdef"),
                modelClient,
                null,
                objectMapper,
                sessionMemoryService,
                artifactProvider);
        RuntimeContextEngineeringService.Invocation invocation =
                service.beginTurn("model-a", memoryKey, "trace-a", "lost-owner");
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(new AgentTool() {
            @Override
            public String getName() {
                return "large_result_tool";
            }

            @Override
            public String getDescription() {
                return "Return an oversized result after the turn lease is lost.";
            }

            @Override
            public Map<String, Object> getParameters() {
                return Map.of("type", "object", "additionalProperties", false);
            }

            @Override
            public Mono<ToolResultBlock> callAsync(ToolCallParam parameter) {
                return Mono.just(ToolResultBlock.text(largeResult));
            }
        });
        ReachAiAgentScopeChatModel publicModel = new ReachAiAgentScopeChatModel(
                "model-a", modelClient, null, objectMapper, null);
        ReActAgent.Builder builder = ReActAgent.builder()
                .name("orders-agent")
                .sysPrompt("Use the tool, then answer.")
                .model(publicModel)
                .toolkit(toolkit)
                .maxIters(4)
                .stateStore(stateStore);
        invocation.configure(toolkit, builder);

        try (ReActAgent agent = builder.build()) {
            Msg result = invocation.call(
                            agent,
                            List.of(message(MsgRole.USER, "fetch the large result")),
                            runtimeContext(memoryKey),
                            publicModel)
                    .block(Duration.ofSeconds(20));
            assertEquals("lease loss handled safely", result.getTextContent());
        }

        assertEquals(2, modelCalls.get());
        assertTrue(secondModelConversation.get().contains(marker));
        verify(sessionMemoryService).renewTurnLease(memoryKey, "lost-owner");
        verifyNoInteractions(artifactService);
    }

    @Test
    void toolResultBeyondArtifactHardLimitIsReducedWithoutPretendingItWasOffloaded() {
        String middleMarker = "HARD-LIMIT-MIDDLE-MUST-NOT-REACH-MODEL";
        String tooLarge = "x".repeat(500_100) + middleMarker + "y".repeat(500_100);
        AtomicInteger publicModelCalls = new AtomicInteger();
        AtomicReference<String> secondModelConversation = new AtomicReference<>();
        RuntimeModelServiceClient modelClient = request -> {
            int call = publicModelCalls.incrementAndGet();
            if (call == 1) {
                return successToolCall("call-hard-limit", "hard_limit_tool");
            }
            secondModelConversation.set(request.getMessages().stream()
                    .map(message -> message.getContent() == null ? "" : message.getContent())
                    .reduce("", (left, right) -> left + "\n" + right));
            return successText("hard limit handled safely");
        };

        RuntimeToolResultArtifactService artifactService = mock(RuntimeToolResultArtifactService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<RuntimeToolResultArtifactService> artifactProvider = mock(ObjectProvider.class);
        when(artifactProvider.getIfAvailable()).thenReturn(artifactService);
        InMemoryAgentStateStore stateStore = new InMemoryAgentStateStore();
        RuntimeSessionMemoryService sessionMemoryService = sessionMemoryService(stateStore);
        RuntimeContextEngineeringService service = new RuntimeContextEngineeringService(
                properties(false, false, true,
                        "0123456789abcdef0123456789abcdef", 1_000_000),
                modelClient,
                null,
                objectMapper,
                sessionMemoryService,
                artifactProvider);
        RuntimeSessionMemoryKey memoryKey = memoryKey();
        RuntimeContextEngineeringService.Invocation invocation =
                service.begin("model-a", memoryKey, "trace-a");
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(new AgentTool() {
            @Override
            public String getName() {
                return "hard_limit_tool";
            }

            @Override
            public String getDescription() {
                return "Return a result beyond the configured artifact hard limit.";
            }

            @Override
            public Map<String, Object> getParameters() {
                return Map.of("type", "object", "additionalProperties", false);
            }

            @Override
            public Mono<ToolResultBlock> callAsync(ToolCallParam parameter) {
                return Mono.just(ToolResultBlock.text(tooLarge));
            }
        });
        ReachAiAgentScopeChatModel publicModel = new ReachAiAgentScopeChatModel(
                "model-a", modelClient, null, objectMapper, null);
        ReActAgent.Builder builder = ReActAgent.builder()
                .name("orders-agent")
                .sysPrompt("Use the tool, then answer without repeating a mutating action.")
                .model(publicModel)
                .toolkit(toolkit)
                .maxIters(4)
                .stateStore(stateStore);
        invocation.configure(toolkit, builder);

        try (ReActAgent agent = builder.build()) {
            Msg result = invocation.call(
                            agent,
                            List.of(message(MsgRole.USER, "fetch the extreme result")),
                            runtimeContext(memoryKey),
                            publicModel)
                    .block(Duration.ofSeconds(20));
            assertEquals("hard limit handled safely", result == null ? null : result.getTextContent());
        }

        assertEquals(2, publicModelCalls.get());
        assertTrue(secondModelConversation.get().contains("exceeded the configured ReachAI Runtime"));
        assertFalse(secondModelConversation.get().contains(middleMarker));
        assertFalse(secondModelConversation.get().contains("tra_"));
        assertEquals(0, invocation.safeMetadata().get("toolResultOffloadCount"));
        assertEquals(1, invocation.safeMetadata().get("toolResultHardLimitDropCount"));
        verifyNoInteractions(artifactService);
    }

    private RuntimeSessionMemoryService sessionMemoryService(InMemoryAgentStateStore stateStore) {
        return new RuntimeSessionMemoryService(
                stateStore,
                null,
                null,
                new RuntimeSessionMemoryProperties(
                        true, "in-memory", "", "", "", false, 40, 65_535, 600));
    }

    private RuntimeSessionMemoryKey memoryKey() {
        return new RuntimeSessionMemoryKey(
                true,
                "tenant-a",
                "user-a",
                "agent-a",
                "public-session",
                "u:owner-hash",
                "a:agent-hash:s:session-hash",
                "AGENT");
    }

    private RuntimeContext runtimeContext(RuntimeSessionMemoryKey memoryKey) {
        return RuntimeContext.builder()
                .userId(memoryKey.stateUserKey())
                .sessionId(memoryKey.stateSessionKey())
                .build();
    }

    private RuntimeContextEngineeringProperties properties(
            boolean compaction,
            boolean overflowRecovery,
            boolean toolResultOffload,
            String secret) {
        return properties(
                compaction, overflowRecovery, toolResultOffload, secret, 16_777_216);
    }

    private RuntimeContextEngineeringProperties properties(
            boolean compaction,
            boolean overflowRecovery,
            boolean toolResultOffload,
            String secret,
            int artifactMaxBytes) {
        return new RuntimeContextEngineeringProperties(
                true,
                compaction,
                overflowRecovery,
                8,
                4_000,
                2,
                2,
                60_000,
                toolResultOffload,
                4_000,
                2_000,
                artifactMaxBytes,
                12_000,
                24,
                200,
                60_000,
                "test-key",
                secret);
    }

    private AgentTool noopTool(String name) {
        return new AgentTool() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public String getDescription() {
                return "Available but not called.";
            }

            @Override
            public Map<String, Object> getParameters() {
                return Map.of("type", "object", "additionalProperties", false);
            }

            @Override
            public Mono<ToolResultBlock> callAsync(ToolCallParam parameter) {
                return Mono.just(ToolResultBlock.text("unused"));
            }
        };
    }

    private Msg message(MsgRole role, String text) {
        return Msg.builder()
                .name(role == MsgRole.ASSISTANT ? "assistant" : "user")
                .role(role)
                .textContent(text)
                .build();
    }

    private String toolResultText(AgentState state) {
        StringBuilder text = new StringBuilder();
        for (Msg message : state.getContext()) {
            for (ContentBlock content : message.getContent()) {
                if (content instanceof ToolResultBlock result) {
                    for (ContentBlock output : result.getOutput()) {
                        if (output instanceof TextBlock block && block.getText() != null) {
                            text.append(block.getText());
                        }
                    }
                }
            }
        }
        return text.toString();
    }

    private ModelChatResult successText(String content) {
        return new ModelChatResult(200, "success", new ModelChatData(
                content,
                "test",
                "test",
                new ModelUsage(1, 1, 2),
                null,
                null,
                "stop"));
    }

    private ModelChatResult successToolCall(String id, String name) {
        Map<String, Object> call = Map.of(
                "id", id,
                "type", "function",
                "function", Map.of(
                        "name", name,
                        "arguments", "{}"));
        return new ModelChatResult(200, "success", new ModelChatData(
                null,
                "test",
                "test",
                new ModelUsage(1, 1, 2),
                null,
                objectMapper.valueToTree(List.of(call)),
                "tool_calls"));
    }
}
