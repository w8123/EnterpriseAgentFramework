package com.enterprise.ai.model.instance;

import com.enterprise.ai.common.dto.ApiResult;
import com.enterprise.ai.model.service.ChatRequest;
import com.enterprise.ai.model.service.ChatResponse;
import com.enterprise.ai.model.service.EmbeddingRequest;
import com.enterprise.ai.model.service.EmbeddingResponse;
import com.enterprise.ai.model.service.ModelRoutingService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/model/instances")
@RequiredArgsConstructor
public class ModelInstanceController {

    private final ModelInstanceService service;
    private final ModelRoutingService routingService;

    @GetMapping
    public ApiResult<List<ModelInstanceResponse>> list(@RequestParam(required = false) String workspaceId,
                                                       @RequestParam(required = false) String modelType,
                                                       @RequestParam(required = false) String provider) {
        return ApiResult.ok(service.list(workspaceId, modelType, provider));
    }

    @GetMapping("/{id}")
    public ApiResult<ModelInstanceResponse> get(@PathVariable("id") String id) {
        return ApiResult.ok(service.get(id));
    }

    @PostMapping
    public ApiResult<ModelInstanceResponse> create(@RequestBody ModelInstanceRequest request) {
        return ApiResult.ok(service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResult<ModelInstanceResponse> update(@PathVariable("id") String id,
                                                   @RequestBody ModelInstanceRequest request) {
        request.setId(id);
        return ApiResult.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResult<Boolean> delete(@PathVariable("id") String id) {
        return ApiResult.ok(service.delete(id));
    }

    @PostMapping("/{id}/test")
    public ApiResult<ModelInstanceTestResponse> test(@PathVariable("id") String id) {
        ModelInstanceResponse instance = service.get(id);
        long start = System.currentTimeMillis();
        try {
            Integer dimension = null;
            if (ModelType.EMBEDDING.name().equals(instance.getModelType())) {
                EmbeddingResponse response = routingService.embed(EmbeddingRequest.builder()
                        .modelInstanceId(id)
                        .texts(List.of("hello"))
                        .build());
                dimension = response.getDimension();
            } else if (ModelType.RERANKER.name().equals(instance.getModelType())) {
                routingService.rerank(com.enterprise.ai.model.service.RerankRequest.builder()
                        .modelInstanceId(id)
                        .query("hello")
                        .documents(List.of("hello world", "goodbye"))
                        .topN(2)
                        .build());
            } else {
                ChatResponse response = routingService.chat(ChatRequest.builder()
                        .modelInstanceId(id)
                        .messages(List.of(ChatRequest.ChatMessage.builder()
                                .role("user")
                                .content("hello")
                                .build()))
                        .build());
                if (response.getContent() == null || response.getContent().isBlank()) {
                    throw new IllegalStateException("empty model response");
                }
            }
            return ApiResult.ok(ModelInstanceTestResponse.builder()
                    .success(true)
                    .latencyMs(System.currentTimeMillis() - start)
                    .message("ok")
                    .modelInstanceId(id)
                    .provider(instance.getProvider())
                    .modelName(instance.getModelName())
                    .modelType(instance.getModelType())
                    .dimension(dimension)
                    .build());
        } catch (Exception e) {
            return ApiResult.ok(ModelInstanceTestResponse.builder()
                    .success(false)
                    .latencyMs(System.currentTimeMillis() - start)
                    .message(e.getMessage())
                    .modelInstanceId(id)
                    .provider(instance.getProvider())
                    .modelName(instance.getModelName())
                    .modelType(instance.getModelType())
                    .build());
        }
    }
}
