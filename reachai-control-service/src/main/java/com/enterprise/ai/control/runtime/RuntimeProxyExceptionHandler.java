package com.enterprise.ai.control.runtime;

import com.enterprise.ai.common.dto.ApiResult;
import feign.FeignException;
import feign.RetryableException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@RestControllerAdvice(assignableTypes = ControlRuntimePublicController.class)
public class RuntimeProxyExceptionHandler {

    private static final Set<String> EXCLUDED_RESPONSE_HEADERS = Set.of(
            "connection",
            "content-length",
            "keep-alive",
            "proxy-authenticate",
            "proxy-authorization",
            "te",
            "trailer",
            "transfer-encoding",
            "upgrade");

    @ExceptionHandler(FeignException.Conflict.class)
    public ResponseEntity<byte[]> handleConflict(FeignException.Conflict exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .headers(copyResponseHeaders(exception.responseHeaders()))
                .body(copyResponseBody(exception));
    }

    @ExceptionHandler(RetryableException.class)
    public ResponseEntity<ApiResult<Void>> handleRuntimeUnavailable(
            RetryableException exception) {
        boolean timedOut = exception.getMessage() != null
                && exception.getMessage().toLowerCase(Locale.ROOT)
                .contains("read timed out");
        String message = timedOut
                ? "Workflow AI 编排超时，当前工作副本未变更。请缩小修改范围后重新生成；如持续失败，请检查 Runtime 与模型服务。"
                : "ReachAI Runtime 暂时不可用，当前工作副本未变更。请稍后重试。";
        return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
                .body(ApiResult.fail(HttpStatus.GATEWAY_TIMEOUT.value(), message));
    }

    private HttpHeaders copyResponseHeaders(Map<String, Collection<String>> responseHeaders) {
        HttpHeaders headers = new HttpHeaders();
        responseHeaders.forEach((name, values) -> {
            if (name == null || EXCLUDED_RESPONSE_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                return;
            }
            values.forEach(value -> headers.add(name, value));
        });
        return headers;
    }

    private byte[] copyResponseBody(FeignException exception) {
        return exception.responseBody()
                .map(ByteBuffer::asReadOnlyBuffer)
                .map(buffer -> {
                    byte[] body = new byte[buffer.remaining()];
                    buffer.get(body);
                    return body;
                })
                .orElseGet(() -> new byte[0]);
    }
}
