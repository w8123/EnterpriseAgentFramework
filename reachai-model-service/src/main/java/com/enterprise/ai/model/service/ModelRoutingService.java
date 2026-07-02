package com.enterprise.ai.model.service;

import com.enterprise.ai.common.exception.BizException;
import com.enterprise.ai.model.instance.EndpointType;
import com.enterprise.ai.model.instance.ModelInstanceEntity;
import com.enterprise.ai.model.instance.ModelInstanceRuntime;
import com.enterprise.ai.model.instance.ModelInstanceService;
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
        return openAiCompatibleRuntimeClient.chat(resolveRuntime(request.getModelInstanceId()), request);
    }

    public Flux<String> chatStream(ChatRequest request) {
        return openAiCompatibleRuntimeClient.chatStream(resolveRuntime(request.getModelInstanceId()), request);
    }

    public EmbeddingResponse embed(EmbeddingRequest request) {
        return openAiCompatibleRuntimeClient.embed(resolveRuntime(request.getModelInstanceId()), request.getTexts());
    }

    public RerankResponse rerank(RerankRequest request) {
        return openAiCompatibleRuntimeClient.rerank(resolveRuntime(request.getModelInstanceId()), request);
    }

    private ModelInstanceRuntime resolveRuntime(String modelInstanceId) {
        if (modelInstanceId == null || modelInstanceId.isBlank()) {
            throw new BizException(400, "modelInstanceId is required");
        }
        ModelInstanceEntity entity = modelInstanceService.getActiveEntity(modelInstanceId);
        ModelInstanceRuntime runtime = modelInstanceService.toRuntime(entity);
        if (!EndpointType.OPENAI_COMPATIBLE.name().equals(runtime.getEndpointType())) {
            throw new BizException(400, "Only OPENAI_COMPATIBLE model instances are supported");
        }
        return runtime;
    }
}
