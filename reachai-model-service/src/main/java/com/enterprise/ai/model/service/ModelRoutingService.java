package com.enterprise.ai.model.service;

import com.enterprise.ai.common.exception.BizException;
import com.enterprise.ai.model.instance.ModelInstanceEntity;
import com.enterprise.ai.model.instance.ModelInstanceRuntime;
import com.enterprise.ai.model.instance.ModelInstanceService;
import com.enterprise.ai.model.instance.ModelProtocol;
import com.enterprise.ai.model.instance.ModelType;
import com.enterprise.ai.model.runtime.OpenAiCompatibleRuntimeClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
@RequiredArgsConstructor
public class ModelRoutingService {

    private final ModelInstanceService modelInstanceService;
    private final OpenAiCompatibleRuntimeClient openAiCompatibleRuntimeClient;

    public ChatResponse chat(ChatRequest request) {
        ModelInstanceRuntime runtime = resolveRuntime(request.getModelInstanceId());
        assertModelType(runtime, ModelType.LLM);
        return openAiCompatibleRuntimeClient.chat(runtime, request);
    }

    public Flux<ModelStreamEvent> chatStreamEvents(ChatRequest request) {
        ModelInstanceRuntime runtime = resolveRuntime(request.getModelInstanceId());
        assertModelType(runtime, ModelType.LLM);
        return openAiCompatibleRuntimeClient.chatStreamEvents(runtime, request);
    }

    public EmbeddingResponse embed(EmbeddingRequest request) {
        ModelInstanceRuntime runtime = resolveRuntime(request.getModelInstanceId());
        assertModelType(runtime, ModelType.EMBEDDING);
        return openAiCompatibleRuntimeClient.embed(runtime, request.getTexts());
    }

    public RerankResponse rerank(RerankRequest request) {
        ModelInstanceRuntime runtime = resolveRuntime(request.getModelInstanceId());
        assertModelType(runtime, ModelType.RERANKER);
        return openAiCompatibleRuntimeClient.rerank(runtime, request);
    }

    private ModelInstanceRuntime resolveRuntime(String modelInstanceId) {
        if (modelInstanceId == null || modelInstanceId.isBlank()) {
            throw new BizException(400, "modelInstanceId is required");
        }
        ModelInstanceEntity entity = modelInstanceService.getActiveEntity(modelInstanceId);
        ModelInstanceRuntime runtime = modelInstanceService.toRuntime(entity);
        if (!ModelProtocol.OPENAI_COMPATIBLE.name().equals(runtime.getProtocol())) {
            throw new BizException(400, "Only OPENAI_COMPATIBLE model instances are supported");
        }
        return runtime;
    }

    private void assertModelType(ModelInstanceRuntime runtime, ModelType expected) {
        if (runtime.getModelType() == null || !expected.name().equals(runtime.getModelType())) {
            throw new BizException(400, expected.name() + " operation requires a " + expected.name()
                    + " model instance, got: " + runtime.getModelType());
        }
    }
}
