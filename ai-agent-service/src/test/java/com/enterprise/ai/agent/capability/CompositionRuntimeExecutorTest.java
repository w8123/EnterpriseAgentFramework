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

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompositionRuntimeExecutorTest {

    @Test
    void executesCompositionGraphAndCallsToolAssetByQualifiedName() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        ToolAssetEntity tool = new ToolAssetEntity();
        tool.setQualifiedName("system.echo");
        tool.setEnabled(true);
        tool.setExecutorType("TEST");
        tool.setExecutorRef("echo");

        CompositionDefinitionEntity composition = new CompositionDefinitionEntity();
        composition.setQualifiedName("system.echo_flow");
        composition.setEnabled(true);
        composition.setGraphSpecJson(objectMapper.writeValueAsString(GraphSpec.builder()
                .entry("input")
                .node(GraphSpec.Node.builder()
                        .id("input")
                        .type("USER_INPUT")
                        .config(Map.of("fields", List.of(Map.of("name", "message", "required", true))))
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
                .edge(GraphSpec.Edge.builder().from("START").to("input").condition("always").build())
                .edge(GraphSpec.Edge.builder().from("input").to("echo").condition("always").build())
                .edge(GraphSpec.Edge.builder().from("echo").to("answer").condition("success").build())
                .edge(GraphSpec.Edge.builder().from("answer").to("END").condition("always").build())
                .build()));

        InMemoryCapabilityAssetService assets = new InMemoryCapabilityAssetService(tool, composition);
        ToolExecutorRegistry registry = new ToolExecutorRegistry(List.of(new TestToolAssetExecutor()));
        ToolRuntimeExecutor toolRuntime = new ToolRuntimeExecutor(assets, registry);
        InteractionRuntimeExecutor interactionRuntime = new InteractionRuntimeExecutor(
                assets, new InteractionSessionService(null, null, objectMapper), objectMapper);
        CompositionRuntimeExecutor runtime = new CompositionRuntimeExecutor(assets, toolRuntime, interactionRuntime, objectMapper);

        CapabilityRuntimeResult result = runtime.execute(CapabilityRuntimeRequest.builder()
                .qualifiedName("system.echo_flow")
                .params(Map.of("message", "hello"))
                .build());

        assertTrue(result.success());
        assertEquals("hello", result.output());
        assertEquals("system.echo_flow", result.qualifiedName());
        assertEquals("answer", result.metadata().get("lastNodeId"));
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

    private static class TestToolAssetExecutor implements ToolAssetExecutor {
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
