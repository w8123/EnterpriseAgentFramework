package com.enterprise.ai.model.instance;

import com.enterprise.ai.common.exception.BizException;
import com.enterprise.ai.model.runtime.OpenAiCompatibleRuntimeClient;
import com.enterprise.ai.model.runtime.UpstreamModel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ModelDiscoveryServiceTest {

    private final ModelInstanceService instances = mock(ModelInstanceService.class);
    private final OpenAiCompatibleRuntimeClient client = mock(OpenAiCompatibleRuntimeClient.class);
    private final ModelDiscoveryService service = new ModelDiscoveryService(instances, client);

    private final ModelInstanceRuntime runtime = ModelInstanceRuntime.builder()
            .provider("tongyi").modelType("LLM").modelName("model-discovery")
            .connectionConfig(Map.of("baseUrl", "https://dashscope.aliyuncs.com/compatible-mode/v1"))
            .build();

    @Test
    void fillsPlaceholderModelNameAndReturnsUpstreamModels() {
        ModelInstanceRequest request = new ModelInstanceRequest();
        when(instances.buildRuntimeForDraft(any())).thenReturn(runtime);
        when(client.listModels(runtime)).thenReturn(List.of(new UpstreamModel("qwen3-max", null, 1L, "LLM")));

        ModelDiscoveryResponse response = service.discover(request);

        assertEquals(ModelDiscoveryService.DISCOVERY_PLACEHOLDER_MODEL, request.getModelName());
        assertTrue(response.success());
        assertEquals(1, response.total());
        assertEquals("qwen3-max", response.models().get(0).id());
    }

    @Test
    void explainsRejectedCredentialWithoutLeakingKey() {
        when(instances.buildRuntimeForDraft(any())).thenReturn(runtime);
        when(client.listModels(runtime)).thenThrow(new BizException(401,
                "Model provider API error: HTTP 401 Authorization: Bearer sk-abcdef1234567890 rejected"));

        ModelDiscoveryResponse response = service.discover(new ModelInstanceRequest());

        assertFalse(response.success());
        assertTrue(response.message().startsWith("API Key 无效或没有权限（HTTP 401）"));
        assertFalse(response.message().contains("sk-abcdef1234567890"));
    }

    @Test
    void discoveryDoesNotReturnProviderOriginalEvenWhenAlreadyExplained() {
        String explained = "模型供应商拒绝了 API Key（HTTP 401）：Key 不对或已失效。请到模型中心修改这个模型的 API Key。"
                + " 供应商原文：PRIVATE_ECHO_MARKER";
        String rejected = ModelDiscoveryService.friendlyMessage(new BizException(401, explained), explained);
        assertTrue(rejected.contains("HTTP 401"));
        assertFalse(rejected.contains("PRIVATE_ECHO_MARKER"));

        String notFound = "模型供应商找不到这个接口或模型（HTTP 404）。请检查模型中心里的接口地址（通常以 /v1 结尾）和模型名称。"
                + " 供应商原文：Not Found";
        assertEquals("该服务地址没有提供模型列表接口（HTTP 404），请直接填写模型名称。",
                ModelDiscoveryService.friendlyMessage(new BizException(404, notFound), notFound));
    }

    @Test
    void discoveryDoesNotExposeUnexpectedExceptionMessage() {
        when(instances.buildRuntimeForDraft(any())).thenReturn(runtime);
        when(client.listModels(runtime)).thenThrow(new IllegalStateException("PRIVATE_ECHO_MARKER"));
        ModelDiscoveryResponse response = service.discover(new ModelInstanceRequest());
        assertFalse(response.success());
        assertFalse(response.message().contains("PRIVATE_ECHO_MARKER"));
    }

    @Test
    void reportsDraftValidationFailureAsResult() {
        when(instances.buildRuntimeForDraft(any())).thenThrow(new IllegalArgumentException("baseUrl is required"));

        ModelDiscoveryResponse response = service.discover(new ModelInstanceRequest());

        assertFalse(response.success());
        assertEquals("baseUrl is required", response.message());
    }
}
