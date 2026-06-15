package com.enterprise.ai.agent.runtime;

import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.agent.runtime.AgentRuntimeProfile;
import com.enterprise.ai.agent.client.ModelServiceClient;
import com.enterprise.ai.agent.runtime.GraphRuntimeContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRuntimeModelValidatorTest {

    @Test
    void acceptsActiveSupportedModelType() {
        AgentRuntimeModelValidator validator = new AgentRuntimeModelValidator(client(instance("LLM", "ACTIVE", 200)));

        assertDoesNotThrow(() -> validator.validate(request("model-1"), capability("LLM")));
    }

    @Test
    void skipsModelLookupWhenLangGraphDoesNotRequireLlm() {
        AgentRuntimeModelValidator validator = new AgentRuntimeModelValidator(failingClient());
        AgentRuntimeRequest request = AgentRuntimeRequest.builder()
                .graphSpec(GraphSpec.builder()
                        .node(GraphSpec.Node.builder()
                                .id("search-action")
                                .type("PAGE_ACTION")
                                .config(Map.of("action", "search"))
                                .build())
                        .build())
                .graphRuntimeContext(GraphRuntimeContext.builder()
                        .runtimeType(AgentRuntimeAdapter.LANGGRAPH4J_RUNTIME_TYPE)
                        .build())
                .build();

        assertDoesNotThrow(() -> validator.validate(request, capability("LLM")));
    }

    @Test
    void rejectsInactiveModelInstance() {
        AgentRuntimeModelValidator validator = new AgentRuntimeModelValidator(client(instance("LLM", "DISABLED", 200)));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> validator.validate(request("model-1"), capability("LLM")));

        assertTrue(ex.getMessage().contains("模型实例不可用"));
    }

    @Test
    void rejectsUnsupportedModelType() {
        AgentRuntimeModelValidator validator = new AgentRuntimeModelValidator(client(instance("EMBEDDING", "ACTIVE", 200)));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> validator.validate(request("model-1"), capability("LLM")));

        assertTrue(ex.getMessage().contains("模型实例类型不兼容"));
    }

    @Test
    void rejectsModelServiceError() {
        AgentRuntimeModelValidator validator = new AgentRuntimeModelValidator(client(instance("LLM", "ACTIVE", 500)));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> validator.validate(request("model-1"), capability("LLM")));

        assertTrue(ex.getMessage().contains("模型实例查询失败"));
    }

    private AgentRuntimeRequest request(String modelInstanceId) {
        return AgentRuntimeRequest.builder()
                .agentRuntimeProfile(AgentRuntimeProfile.builder()
                        .modelInstanceId(modelInstanceId)
                        .build())
                .build();
    }

    private AgentRuntimeCapability capability(String... modelTypes) {
        return AgentRuntimeCapability.builder()
                .runtimeType(AgentRuntimeAdapter.DEFAULT_RUNTIME_TYPE)
                .available(true)
                .supportedModelTypes(List.of(modelTypes))
                .build();
    }

    private ModelServiceClient.ModelInstanceResult instance(String modelType, String status, int code) {
        return new ModelServiceClient.ModelInstanceResult(
                code,
                code == 200 ? "success" : "error",
                code == 200 ? new ModelServiceClient.ModelInstanceData(
                        "model-1",
                        "Model One",
                        "openai",
                        modelType,
                        "gpt-x",
                        "OPENAI_COMPATIBLE",
                        null,
                        null,
                        null,
                        status,
                        null) : null);
    }

    private ModelServiceClient client(ModelServiceClient.ModelInstanceResult result) {
        return new ModelServiceClient() {
            @Override
            public ModelChatResult chat(ModelChatRequest request) {
                throw new UnsupportedOperationException();
            }

            @Override
            public ModelEmbeddingResult embed(ModelEmbeddingRequest request) {
                throw new UnsupportedOperationException();
            }

            @Override
            public ModelInstanceResult getModelInstance(String id) {
                return result;
            }
        };
    }

    private ModelServiceClient failingClient() {
        return new ModelServiceClient() {
            @Override
            public ModelChatResult chat(ModelChatRequest request) {
                throw new UnsupportedOperationException();
            }

            @Override
            public ModelEmbeddingResult embed(ModelEmbeddingRequest request) {
                throw new UnsupportedOperationException();
            }

            @Override
            public ModelInstanceResult getModelInstance(String id) {
                throw new AssertionError("model service should not be called for model-free graph");
            }
        };
    }
}
