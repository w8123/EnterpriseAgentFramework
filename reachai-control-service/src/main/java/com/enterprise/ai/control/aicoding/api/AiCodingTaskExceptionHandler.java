package com.enterprise.ai.control.aicoding.api;

import feign.FeignException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@Slf4j
@RestControllerAdvice(assignableTypes = {
        AiCodingCredentialPolicyConsoleController.class,
        AiCodingTaskConsoleController.class,
        AiCodingHandoffController.class,
        AiCodingTaskProtocolController.class
})
public class AiCodingTaskExceptionHandler {

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> unreadableRequest(
            HttpMessageNotReadableException ex) {
        return error(
                HttpStatus.BAD_REQUEST,
                "AI_CODING_TASK_INVALID_JSON",
                "请求体不是有效的 JSON，请检查字段名称、引号和编码。");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> unsupportedMediaType(
            HttpMediaTypeNotSupportedException ex) {
        return error(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "AI_CODING_TASK_UNSUPPORTED_MEDIA_TYPE",
                "请求体必须使用 Content-Type: application/json; charset=utf-8。");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException ex) {
        return error(HttpStatus.BAD_REQUEST, "AI_CODING_TASK_INVALID_REQUEST", ex.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> conflict(IllegalStateException ex) {
        return error(HttpStatus.CONFLICT, "AI_CODING_TASK_CONFLICT", ex.getMessage());
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> status(ResponseStatusException ex) {
        return error(
                HttpStatus.valueOf(ex.getStatusCode().value()),
                "AI_CODING_TASK_ACCESS_ERROR",
                ex.getReason());
    }

    @ExceptionHandler(FeignException.class)
    public ResponseEntity<Map<String, Object>> dependencyUnavailable(
            FeignException ex) {
        log.error("AI Coding 内部依赖调用失败", ex);
        return error(
                HttpStatus.SERVICE_UNAVAILABLE,
                "AI_CODING_DEPENDENCY_UNAVAILABLE",
                "ReachAI 内部依赖暂时不可用，请稍后重试。");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unexpected(Exception ex) {
        log.error("AI Coding 接口发生未预期异常", ex);
        return error(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "AI_CODING_INTERNAL_ERROR",
                "AI Coding 服务暂时不可用，请稍后重试。");
    }

    private static ResponseEntity<Map<String, Object>> error(
            HttpStatus status,
            String code,
            String message) {
        return ResponseEntity.status(status).body(Map.of(
                "schema", "reachai.ai-coding.error.v1",
                "code", code,
                "message", message == null ? "" : message));
    }
}
