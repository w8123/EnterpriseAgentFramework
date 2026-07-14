package com.enterprise.ai.control.runtime;

import feign.FeignException;
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
