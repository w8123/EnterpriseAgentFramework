package com.enterprise.ai.model.instance;

import com.enterprise.ai.common.exception.BizException;
import com.enterprise.ai.model.runtime.OpenAiCompatibleRuntimeClient;
import com.enterprise.ai.model.service.ChatRequest;
import com.enterprise.ai.model.service.ChatResponse;
import com.enterprise.ai.model.service.EmbeddingResponse;
import com.enterprise.ai.model.service.RerankRequest;
import com.enterprise.ai.model.service.RerankResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ModelInstanceTestService {

    private final ModelInstanceService modelInstanceService;
    private final OpenAiCompatibleRuntimeClient openAiCompatibleRuntimeClient;

    public ModelInstanceTestResponse testSaved(String id) {
        ModelInstanceEntity entity = modelInstanceService.getEntityForTest(id);
        ModelInstanceRuntime runtime = modelInstanceService.toRuntime(entity);
        return runTest(runtime, true);
    }

    public ModelInstanceTestResponse testDraft(ModelInstanceRequest request) {
        ModelInstanceRuntime runtime;
        try {
            runtime = modelInstanceService.buildRuntimeForDraft(request);
        } catch (IllegalArgumentException e) {
            return validationFailure(request, e.getMessage());
        } catch (BizException e) {
            if (e.getCode() == 404 || e.getCode() >= 500 || isArchivedError(e)) {
                throw e;
            }
            return validationFailure(request, e.getMessage());
        }
        return runTest(runtime, false);
    }

    private ModelInstanceTestResponse runTest(ModelInstanceRuntime runtime, boolean persistResult) {
        long start = System.currentTimeMillis();
        try {
            Integer dimension = null;
            ModelType modelType = ModelType.valueOf(runtime.getModelType());
            switch (modelType) {
                case EMBEDDING -> {
                    EmbeddingResponse response = openAiCompatibleRuntimeClient.embed(runtime, List.of("hello"));
                    assertEmbeddingSuccess(response);
                    dimension = response.getDimension();
                }
                case RERANKER -> {
                    RerankResponse response = openAiCompatibleRuntimeClient.rerank(runtime, RerankRequest.builder()
                            .query("hello")
                            .documents(List.of("hello world", "goodbye"))
                            .topN(2)
                            .build());
                    assertRerankSuccess(response);
                }
                case LLM -> {
                    ChatResponse response = openAiCompatibleRuntimeClient.chat(runtime, ChatRequest.builder()
                            .messages(List.of(ChatRequest.ChatMessage.builder()
                                    .role("user")
                                    .content("hello")
                                    .build()))
                            .build());
                    if (response.getContent() == null || response.getContent().isBlank()) {
                        throw new IllegalStateException("empty model response");
                    }
                }
            }
            long latency = System.currentTimeMillis() - start;
            if (persistResult && runtime.getId() != null) {
                modelInstanceService.updateTestResult(runtime.getId(), true, latency, null);
            }
            return ModelInstanceTestResponse.builder()
                    .success(true)
                    .latencyMs(latency)
                    .message("ok")
                    .modelInstanceId(runtime.getId())
                    .provider(runtime.getProvider())
                    .modelName(runtime.getModelName())
                    .modelType(runtime.getModelType())
                    .lastTestStatus(persistResult ? ModelTestStatus.SUCCESS.name() : null)
                    .dimension(dimension)
                    .build();
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            String message = ConnectionConfigSupport.sanitizeError(
                    e instanceof BizException be ? be.getMessage() : e.getMessage());
            if (persistResult && runtime.getId() != null) {
                modelInstanceService.updateTestResult(runtime.getId(), false, latency, message);
            }
            return ModelInstanceTestResponse.builder()
                    .success(false)
                    .latencyMs(latency)
                    .message(message)
                    .modelInstanceId(runtime.getId())
                    .provider(runtime.getProvider())
                    .modelName(runtime.getModelName())
                    .modelType(runtime.getModelType())
                    .lastTestStatus(persistResult ? ModelTestStatus.FAILED.name() : null)
                    .build();
        }
    }

    private static void assertEmbeddingSuccess(EmbeddingResponse response) {
        if (response == null || response.getDimension() <= 0) {
            throw new IllegalStateException("embedding dimension must be > 0");
        }
        if (response.getEmbeddings() == null || response.getEmbeddings().isEmpty()) {
            throw new IllegalStateException("embedding vectors must not be empty");
        }
        boolean hasNonEmpty = response.getEmbeddings().stream()
                .anyMatch(vector -> vector != null && !vector.isEmpty());
        if (!hasNonEmpty) {
            throw new IllegalStateException("embedding must contain at least one non-empty vector");
        }
    }

    private static void assertRerankSuccess(RerankResponse response) {
        if (response == null || response.getResults() == null || response.getResults().isEmpty()) {
            throw new IllegalStateException("rerank results must not be empty");
        }
        boolean hasValidIndex = response.getResults().stream()
                .anyMatch(result -> result != null && result.getIndex() >= 0);
        if (!hasValidIndex) {
            throw new IllegalStateException("rerank must contain at least one result with valid index");
        }
    }

    private ModelInstanceTestResponse validationFailure(ModelInstanceRequest request, String message) {
        return ModelInstanceTestResponse.builder()
                .success(false)
                .latencyMs(0L)
                .message(ConnectionConfigSupport.sanitizeError(message))
                .modelInstanceId(request != null && request.getId() != null ? request.getId().trim() : null)
                .provider(request != null ? request.getProvider() : null)
                .modelName(request != null ? request.getModelName() : null)
                .modelType(request != null && request.getModelType() != null ? request.getModelType().name() : null)
                .build();
    }

    private static boolean isArchivedError(BizException e) {
        String message = e.getMessage();
        return message != null && message.toLowerCase().contains("archived");
    }
}
