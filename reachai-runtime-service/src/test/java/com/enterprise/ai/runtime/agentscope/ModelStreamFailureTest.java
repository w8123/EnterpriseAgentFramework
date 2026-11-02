package com.enterprise.ai.runtime.agentscope;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelStreamFailureTest {

    @Test
    void mapsLengthToOutputTokenLimit() {
        ModelStreamFailure failure = ModelStreamFailure.emptyResponse(diag("length", true, true));
        assertEquals(ModelStreamFailure.MODEL_OUTPUT_TOKEN_LIMIT, failure.code());
        assertFalse(failure.safeMessage().contains("secret"));
    }

    @Test
    void mapsContentFilter() {
        assertEquals(ModelStreamFailure.MODEL_CONTENT_FILTERED,
                ModelStreamFailure.emptyResponse(diag("content_filter", true, true)).code());
    }

    @Test
    void mapsInsufficientResource() {
        assertEquals(ModelStreamFailure.MODEL_INSUFFICIENT_SYSTEM_RESOURCE,
                ModelStreamFailure.emptyResponse(diag("insufficient_system_resource", true, true)).code());
    }

    @Test
    void mapsStopEmptyToEmptyResponse() {
        assertEquals(ModelStreamFailure.MODEL_EMPTY_RESPONSE,
                ModelStreamFailure.emptyResponse(diag("stop", true, true)).code());
    }

    @Test
    void mapsMissingCompletedToInterrupted() {
        assertEquals(ModelStreamFailure.MODEL_STREAM_INTERRUPTED,
                ModelStreamFailure.emptyResponse(diag(null, true, false)).code());
    }

    @Test
    void mapsProviderContextOverflowToStableDefinitiveCodeWithoutLeakingPayload() {
        ModelStreamFailure failure = ModelStreamFailure.providerError(
                "context_length_exceeded",
                "maximum context length; upstream-secret=do-not-return",
                diag(null, false, false));

        assertEquals(ModelStreamFailure.MODEL_CONTEXT_LENGTH_EXCEEDED, failure.code());
        assertTrue(failure.isContextLengthExceeded());
        assertTrue(failure.isDefinitiveProviderFailure());
        assertFalse(failure.safeMessage().contains("upstream-secret"));
        assertFalse(failure.toSafeMetadata().toString().contains("do-not-return"));
    }

    @Test
    void mapsSanitizedModelServiceClassifications() {
        ModelStreamFailure context = ModelStreamFailure.providerError(null,
                "模型供应商拒绝了请求（HTTP 400）：上下文长度超过模型限制，请缩短输入或清理会话后重试。", null);
        assertEquals(ModelStreamFailure.MODEL_CONTEXT_LENGTH_EXCEEDED, context.code());
        ModelStreamFailure balance = ModelStreamFailure.providerError(null,
                "模型供应商账户余额不足（HTTP 400）。请到供应商控制台检查额度，或换一个可用的模型。", null);
        assertEquals(ModelStreamFailure.MODEL_PROVIDER_BALANCE_INSUFFICIENT, balance.code());
    }

    @Test
    void providerErrorShowsUpstreamStatusAndCodeWithoutLeakingPayload() {
        ModelStreamFailure failure = ModelStreamFailure.providerError(
                null,
                "Model provider API error: HTTP 403 {\"error\":{\"message\":\"Access to model denied. upstream-secret\","
                        + "\"type\":\"AccessDenied.Unpurchased\",\"code\":\"AccessDenied.Unpurchased\"},"
                        + "\"request_id\":\"e3ea2a9c\"}",
                diag(null, false, false));

        assertEquals(ModelStreamFailure.MODEL_PROVIDER_REQUEST_FAILED, failure.code());
        assertTrue(failure.safeMessage().contains("HTTP 403"));
        assertTrue(failure.safeMessage().contains("AccessDenied.Unpurchased"));
        assertTrue(failure.safeMessage().contains("无权使用该模型"));
        assertFalse(failure.safeMessage().contains("upstream-secret"));
        assertFalse(failure.safeMessage().contains("e3ea2a9c"));
    }

    @Test
    void providerErrorWithoutRecognizableDetailKeepsGenericMessage() {
        ModelStreamFailure failure = ModelStreamFailure.providerError(null, "connection refused", null);
        assertEquals("模型供应商调用失败，请到 ReachAI 模型中心重新测试并检查连接配置后重试。", failure.safeMessage());
    }

    @Test
    void modelServiceTransportHintIsShownAsIs() {
        String hint = "连不上模型服务（http://127.0.0.1:11434）：对方拒绝连接。请检查模型中心里的接口地址和端口是否正确，"
                + "本地模型（如 Ollama）请确认已经启动。";
        ModelStreamFailure failure = ModelStreamFailure.providerError("502", hint, null);
        assertEquals(ModelStreamFailure.MODEL_PROVIDER_REQUEST_FAILED, failure.code());
        assertEquals(hint, failure.safeMessage());
    }

    private static ModelStreamDiagnostics diag(String finishReason, boolean consumed, boolean completed) {
        return ModelStreamDiagnostics.builder()
                .modelInstanceId("seed-deepseek-v4-flash")
                .finishReason(finishReason)
                .reasoningDeltaCount(3)
                .reasoningLength(120)
                .consumedAnyEvent(consumed)
                .completedReceived(completed)
                .build();
    }
}
