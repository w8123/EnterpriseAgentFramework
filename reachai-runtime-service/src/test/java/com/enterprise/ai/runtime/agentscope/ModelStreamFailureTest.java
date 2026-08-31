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
