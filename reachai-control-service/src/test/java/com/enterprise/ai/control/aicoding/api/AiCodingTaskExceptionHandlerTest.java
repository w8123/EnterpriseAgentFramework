package com.enterprise.ai.control.aicoding.api;

import feign.FeignException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class AiCodingTaskExceptionHandlerTest {

    private final AiCodingTaskExceptionHandler handler =
            new AiCodingTaskExceptionHandler();

    @Test
    void returnsBadRequestForUnreadableJson() {
        var response = handler.unreadableRequest(
                mock(HttpMessageNotReadableException.class));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertError(
                response.getBody(),
                "AI_CODING_TASK_INVALID_JSON");
    }

    @Test
    void returnsServiceUnavailableWithoutLeakingDependencyDetails() {
        var response = handler.dependencyUnavailable(
                mock(FeignException.class));

        assertEquals(
                HttpStatus.SERVICE_UNAVAILABLE,
                response.getStatusCode());
        assertError(
                response.getBody(),
                "AI_CODING_DEPENDENCY_UNAVAILABLE");
        assertEquals(
                "ReachAI 内部依赖暂时不可用，请稍后重试。",
                response.getBody().get("message"));
    }

    @Test
    void returnsUnsupportedMediaTypeInsteadOfGeneric500() {
        var response = handler.unsupportedMediaType(
                mock(HttpMediaTypeNotSupportedException.class));

        assertEquals(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                response.getStatusCode());
        assertError(
                response.getBody(),
                "AI_CODING_TASK_UNSUPPORTED_MEDIA_TYPE");
    }

    @Test
    void returnsInternalServerErrorForUnexpectedFailure() {
        var response = handler.unexpected(
                new RuntimeException("sensitive internal detail"));

        assertEquals(
                HttpStatus.INTERNAL_SERVER_ERROR,
                response.getStatusCode());
        assertError(response.getBody(), "AI_CODING_INTERNAL_ERROR");
        assertEquals(
                "AI Coding 服务暂时不可用，请稍后重试。",
                response.getBody().get("message"));
    }

    private static void assertError(
            Map<String, Object> body,
            String code) {
        assertEquals(
                "reachai.ai-coding.error.v1",
                body.get("schema"));
        assertEquals(code, body.get("code"));
    }
}
