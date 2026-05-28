package com.enterprise.ai.agent.capability;

import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.agent.runtime.CapabilityRuntimeRequest;
import com.enterprise.ai.agent.runtime.CapabilityRuntimeResult;
import com.enterprise.ai.agent.runtime.CompositionRuntimeExecutor;
import com.enterprise.ai.agent.runtime.InteractionRuntimeExecutor;
import com.enterprise.ai.agent.runtime.InteractionSessionService;
import com.enterprise.ai.agent.runtime.ToolExecutorRegistry;
import com.enterprise.ai.agent.runtime.ToolRuntimeExecutor;
import com.enterprise.ai.agent.runtime.ToolRuntimeRequest;
import com.enterprise.ai.agent.runtime.ToolRuntimeResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InteractionRuntimeExecutorTest {

    @Test
    void collectInputInteractionSuspendsAndResumeContinuesComposition() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        ToolAssetEntity tool = new ToolAssetEntity();
        tool.setQualifiedName("system.echo");
        tool.setEnabled(true);
        tool.setExecutorType("TEST");
        tool.setExecutorRef("echo");

        CompositionDefinitionEntity composition = new CompositionDefinitionEntity();
        composition.setCapabilityCode("system");
        composition.setQualifiedName("system.interactive_echo");
        composition.setEnabled(true);
        composition.setGraphSpecJson(objectMapper.writeValueAsString(GraphSpec.builder()
                .entry("collect")
                .node(GraphSpec.Node.builder()
                        .id("collect")
                        .type("INTERACTION")
                        .config(Map.of(
                                "interactionType", "COLLECT_INPUT",
                                "title", "Message",
                                "fields", List.of(Map.of(
                                        "key", "message",
                                        "label", "Message",
                                        "type", "string",
                                        "required", true))))
                        .build())
                .node(GraphSpec.Node.builder()
                        .id("echo")
                        .type("TOOL")
                        .config(Map.of(
                                "qualifiedName", "system.echo",
                                "inputMapping", Map.of("message", "params.message"),
                                "outputAlias", "echoed"))
                        .build())
                .node(GraphSpec.Node.builder()
                        .id("answer")
                        .type("ANSWER")
                        .config(Map.of("template", "{{ echoed.text }}"))
                        .build())
                .edge(GraphSpec.Edge.builder().from("START").to("collect").condition("always").build())
                .edge(GraphSpec.Edge.builder().from("collect").to("echo").condition("always").build())
                .edge(GraphSpec.Edge.builder().from("echo").to("answer").condition("success").build())
                .edge(GraphSpec.Edge.builder().from("answer").to("END").condition("always").build())
                .build()));

        InMemoryCapabilityAssetService assets = new InMemoryCapabilityAssetService(tool, composition);
        InMemoryInteractionSessionService sessions = new InMemoryInteractionSessionService(objectMapper);
        ToolRuntimeExecutor toolRuntime = new ToolRuntimeExecutor(assets,
                new ToolExecutorRegistry(List.of(new EchoTestExecutor())));
        InteractionRuntimeExecutor interactionRuntime = new InteractionRuntimeExecutor(assets, sessions, objectMapper);
        CompositionRuntimeExecutor runtime = new CompositionRuntimeExecutor(assets, toolRuntime, interactionRuntime, objectMapper);

        CapabilityRuntimeResult waiting = runtime.execute(CapabilityRuntimeRequest.builder()
                .qualifiedName("system.interactive_echo")
                .params(Map.of())
                .build());

        assertEquals("WAITING_USER", waiting.status());
        assertEquals("system.interactive_echo", waiting.qualifiedName());
        assertNotNull(waiting.metadata().get("interactionSessionId"));
        assertEquals("collect", waiting.metadata().get("nodeId"));
        Map<String, Object> uiRequest = castMap(waiting.metadata().get("uiRequest"));
        assertEquals("FORM", uiRequest.get("component"));
        assertEquals(List.of("message"), uiRequest.get("missing"));

        CapabilityRuntimeResult resumed = runtime.resumeInteraction(
                String.valueOf(waiting.metadata().get("interactionSessionId")),
                Map.of("message", "hello"),
                Map.of());

        assertEquals("SUCCESS", resumed.status());
        assertTrue(resumed.success());
        assertEquals("hello", resumed.output());
        assertEquals("answer", resumed.metadata().get("lastNodeId"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object raw) {
        return (Map<String, Object>) raw;
    }

    private static class InMemoryCapabilityAssetService extends CapabilityAssetService {
        private final ToolAssetEntity tool;
        private final CompositionDefinitionEntity composition;

        InMemoryCapabilityAssetService(ToolAssetEntity tool, CompositionDefinitionEntity composition) {
            super(null, null, null, null, null);
            this.tool = tool;
            this.composition = composition;
        }

        @Override
        public Optional<ToolAssetEntity> findToolByQualifiedName(String qualifiedName) {
            return tool.getQualifiedName().equals(qualifiedName) ? Optional.of(tool) : Optional.empty();
        }

        @Override
        public Optional<CompositionDefinitionEntity> findCompositionByQualifiedName(String qualifiedName) {
            return composition.getQualifiedName().equals(qualifiedName) ? Optional.of(composition) : Optional.empty();
        }
    }

    private static class InMemoryInteractionSessionService extends InteractionSessionService {
        private final Map<String, InteractionSessionEntity> sessions = new LinkedHashMap<>();
        private final ObjectMapper objectMapper;

        InMemoryInteractionSessionService(ObjectMapper objectMapper) {
            super(null, null, objectMapper);
            this.objectMapper = objectMapper;
        }

        @Override
        public InteractionSessionEntity createWaitingSession(String compositionQualifiedName,
                                                             String nodeId,
                                                             String interactionType,
                                                             Map<String, Object> state,
                                                             Map<String, Object> uiRequest) {
            InteractionSessionEntity session = new InteractionSessionEntity();
            session.setId("session-" + (sessions.size() + 1));
            session.setRunId("run-1");
            session.setCompositionQualifiedName(compositionQualifiedName);
            session.setNodeId(nodeId);
            session.setInteractionType(interactionType);
            session.setStatus("WAITING_USER");
            session.setStateJson(write(state));
            session.setUiRequestJson(write(uiRequest));
            session.setCreateTime(LocalDateTime.now());
            session.setUpdateTime(LocalDateTime.now());
            sessions.put(session.getId(), session);
            return session;
        }

        @Override
        public Optional<InteractionSessionEntity> findSession(String sessionId) {
            return Optional.ofNullable(sessions.get(sessionId));
        }

        @Override
        public void markSubmitted(String sessionId, Map<String, Object> submittedPayload) {
            InteractionSessionEntity session = sessions.get(sessionId);
            session.setStatus("SUBMITTED");
            session.setSubmittedPayloadJson(write(submittedPayload));
        }

        @Override
        public void markCompleted(String sessionId, Object output) {
            sessions.get(sessionId).setStatus("COMPLETED");
        }

        private String write(Object value) {
            try {
                return objectMapper.writeValueAsString(value);
            } catch (Exception ex) {
                throw new IllegalArgumentException(ex);
            }
        }
    }

    private static class EchoTestExecutor implements ToolAssetExecutor {
        @Override
        public String executorType() {
            return "TEST";
        }

        @Override
        public ToolRuntimeResult execute(ToolAssetEntity tool, ToolRuntimeRequest request) {
            return ToolRuntimeResult.success(tool.getQualifiedName(), Map.of("text", request.args().get("message")));
        }
    }
}
