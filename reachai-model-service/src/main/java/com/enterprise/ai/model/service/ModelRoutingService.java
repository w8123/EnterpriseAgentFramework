package com.enterprise.ai.model.service;

import com.enterprise.ai.common.exception.BizException;
import com.enterprise.ai.model.instance.ModelInstanceEntity;
import com.enterprise.ai.model.instance.ModelInstanceRuntime;
import com.enterprise.ai.model.instance.ModelInstanceRuntimeCache;
import com.enterprise.ai.model.instance.ModelInstanceService;
import com.enterprise.ai.model.instance.ModelProtocol;
import com.enterprise.ai.model.instance.ModelType;
import com.enterprise.ai.model.runtime.OpenAiCompatibleRuntimeClient;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
@RequiredArgsConstructor
public class ModelRoutingService {

    private static final Logger log = LoggerFactory.getLogger(ModelRoutingService.class);

    private final ModelInstanceService modelInstanceService;
    private final ModelInstanceRuntimeCache runtimeCache;
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
        long started = System.nanoTime();
        ModelInstanceRuntime cached = runtimeCache.getIfPresent(modelInstanceId);
        if (cached != null) {
            long cacheHitMs = (System.nanoTime() - started) / 1_000_000L;
            if (cacheHitMs >= 50L) {
                log.info("model.resolveRuntime cacheHitMs={} modelInstanceId={}", cacheHitMs, modelInstanceId);
            }
            return cached;
        }
        ModelInstanceEntity entity = modelInstanceService.getActiveEntity(modelInstanceId);
        ModelInstanceRuntime runtime = modelInstanceService.toRuntime(entity);
        long resolveMs = (System.nanoTime() - started) / 1_000_000L;
        if (resolveMs >= 100L) {
            log.info("model.resolveRuntime dbMs={} modelInstanceId={} status=ACTIVE",
                    resolveMs, modelInstanceId);
        }
        if (!ModelProtocol.OPENAI_COMPATIBLE.name().equals(runtime.getProtocol())) {
            throw new BizException(400, "Only OPENAI_COMPATIBLE model instances are supported");
        }
        runtimeCache.put(modelInstanceId, runtime);
        return runtime;
    }

    private void assertModelType(ModelInstanceRuntime runtime, ModelType expected) {
        if (runtime.getModelType() == null || !expected.name().equals(runtime.getModelType())) {
            throw new BizException(400, expected.name() + " operation requires a " + expected.name()
                    + " model instance, got: " + runtime.getModelType());
        }
    }
}
