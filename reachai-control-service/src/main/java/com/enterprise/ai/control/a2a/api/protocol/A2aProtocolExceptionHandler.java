package com.enterprise.ai.control.a2a.api.protocol;

import com.enterprise.ai.control.a2a.domain.A2aDomainException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import jakarta.servlet.http.HttpServletRequest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.enterprise.ai.control.a2a.application.protocol.A2aProtocolModels.ErrorEnvelope;
import static com.enterprise.ai.control.a2a.application.protocol.A2aProtocolModels.ErrorStatus;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = A2aHttpJsonController.class)
public class A2aProtocolExceptionHandler {

    static final String ERROR_CODE_ATTRIBUTE = A2aProtocolExceptionHandler.class.getName() + ".errorCode";

    @ExceptionHandler(A2aDomainException.class)
    public ResponseEntity<ErrorEnvelope> handleDomain(
            A2aDomainException exception, HttpServletRequest request) {
        Mapping mapping = mapping(exception.code());
        request.setAttribute(ERROR_CODE_ATTRIBUTE, mapping.reason());
        String message = mapping.exposeDetail()
                ? exception.getMessage() : "The A2A request could not be processed";
        return response(request, mapping.status(), mapping.rpcStatus(), mapping.reason(), message);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentNotValidException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorEnvelope> handleInvalidRequest(
            Exception ignored, HttpServletRequest request) {
        return response(request, HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "INVALID_ARGUMENT",
                "The request does not conform to the A2A HTTP+JSON contract");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorEnvelope> handleMediaType(
            HttpMediaTypeNotSupportedException ignored, HttpServletRequest request) {
        return response(request, HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "CONTENT_TYPE_NOT_SUPPORTED",
                "Content-Type application/a2a+json is required");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorEnvelope> handleTooLarge(
            MaxUploadSizeExceededException ignored, HttpServletRequest request) {
        return response(request, HttpStatus.PAYLOAD_TOO_LARGE, "RESOURCE_EXHAUSTED", "REQUEST_TOO_LARGE",
                "The request exceeds the configured size limit");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorEnvelope> handleUnexpected(
            Exception ignored, HttpServletRequest request) {
        return response(request, HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL", "INTERNAL",
                "The A2A request could not be processed");
    }

    private ResponseEntity<ErrorEnvelope> response(
            HttpServletRequest request,
            HttpStatus status, String rpcStatus, String reason, String message) {
        Map<String, Object> errorInfo = new LinkedHashMap<>();
        errorInfo.put("@type", "type.googleapis.com/google.rpc.ErrorInfo");
        errorInfo.put("reason", reason);
        errorInfo.put("domain", "a2a-protocol.org");
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status)
                .contentType(A2aResponseMediaTypes.negotiate(request))
                .header(A2aProtocolRequestGuard.VERSION_HEADER, "1.0")
                .cacheControl(org.springframework.http.CacheControl.noStore());
        if (status == HttpStatus.UNAUTHORIZED) {
            builder.header(HttpHeaders.WWW_AUTHENTICATE,
                    "ApiKey realm=\"ReachAI A2A Hub\", header=\"X-ReachAI-A2A-Key\"");
        }
        if (status == HttpStatus.TOO_MANY_REQUESTS) {
            builder.header(HttpHeaders.RETRY_AFTER, "60");
        }
        return builder.body(new ErrorEnvelope(new ErrorStatus(
                status.value(), rpcStatus, message, List.of(errorInfo))));
    }

    private Mapping mapping(String code) {
        if (code == null) {
            return internal("INTERNAL");
        }
        return switch (code) {
            case "A2A_AUTHENTICATION_FAILED", "A2A_AUTHENTICATION_REQUIRED" ->
                    new Mapping(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "UNAUTHENTICATED", true);
            case "A2A_TASK_NOT_FOUND", "A2A_CONTEXT_NOT_FOUND", "A2A_PUBLICATION_NOT_FOUND" ->
                    new Mapping(HttpStatus.NOT_FOUND, "NOT_FOUND", "TASK_NOT_FOUND", true);
            case "A2A_TASK_NOT_CANCELABLE" ->
                    new Mapping(HttpStatus.BAD_REQUEST, "FAILED_PRECONDITION", "TASK_NOT_CANCELABLE", true);
            case "A2A_PUSH_NOTIFICATION_NOT_SUPPORTED" ->
                    new Mapping(HttpStatus.BAD_REQUEST, "FAILED_PRECONDITION",
                            "PUSH_NOTIFICATION_NOT_SUPPORTED", true);
            case "A2A_UNSUPPORTED_OPERATION" ->
                    new Mapping(HttpStatus.BAD_REQUEST, "FAILED_PRECONDITION", "UNSUPPORTED_OPERATION", true);
            case "A2A_CONTENT_TYPE_NOT_SUPPORTED" ->
                    new Mapping(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "CONTENT_TYPE_NOT_SUPPORTED", true);
            case "A2A_INVALID_AGENT_RESPONSE" ->
                    new Mapping(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL", "INVALID_AGENT_RESPONSE", false);
            case "A2A_EXTENDED_AGENT_CARD_NOT_CONFIGURED" ->
                    new Mapping(HttpStatus.BAD_REQUEST, "FAILED_PRECONDITION",
                            "EXTENDED_AGENT_CARD_NOT_CONFIGURED", true);
            case "A2A_VERSION_NOT_SUPPORTED" ->
                    new Mapping(HttpStatus.BAD_REQUEST, "FAILED_PRECONDITION", "VERSION_NOT_SUPPORTED", true);
            case "A2A_MESSAGE_IDEMPOTENCY_CONFLICT", "A2A_TASK_VERSION_CONFLICT",
                    "A2A_TASK_CONFLICT", "A2A_MESSAGE_CONFLICT" ->
                    new Mapping(HttpStatus.CONFLICT, "ABORTED", "IDEMPOTENCY_CONFLICT", true);
            case "A2A_CONCURRENT_TASK_LIMIT_EXCEEDED", "A2A_RATE_LIMIT_EXCEEDED" ->
                    new Mapping(HttpStatus.TOO_MANY_REQUESTS, "RESOURCE_EXHAUSTED",
                            "RESOURCE_EXHAUSTED", true);
            case "A2A_REQUEST_TOO_LARGE" ->
                    new Mapping(HttpStatus.PAYLOAD_TOO_LARGE, "RESOURCE_EXHAUSTED", "REQUEST_TOO_LARGE", true);
            case "A2A_CONTENT_KEY_NOT_CONFIGURED", "A2A_CONTENT_KEY_UNAVAILABLE",
                    "A2A_AUTHENTICATION_METHOD_UNAVAILABLE", "A2A_TRUST_PROFILE_NOT_ACTIVE" ->
                    new Mapping(HttpStatus.SERVICE_UNAVAILABLE, "UNAVAILABLE", "SERVICE_UNAVAILABLE", false);
            case "A2A_CONTENT_ENCRYPTION_FAILED", "A2A_CONTENT_INTEGRITY_FAILED",
                    "A2A_TASK_PERSISTENCE_FAILED", "A2A_TASK_EVENT_CONFLICT", "A2A_OUTBOX_CONFLICT" ->
                    internal("INTERNAL");
            default -> {
                if (code.endsWith("_FORBIDDEN") || code.contains("NOT_ALLOWED")) {
                    yield new Mapping(HttpStatus.FORBIDDEN, "PERMISSION_DENIED", "PERMISSION_DENIED", true);
                }
                yield new Mapping(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT",
                        normalizeReason(code), true);
            }
        };
    }

    private Mapping internal(String reason) {
        return new Mapping(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL", reason, false);
    }

    private String normalizeReason(String code) {
        String normalized = code.toUpperCase(Locale.ROOT);
        return normalized.startsWith("A2A_") ? normalized.substring(4) : normalized;
    }

    private record Mapping(
            HttpStatus status,
            String rpcStatus,
            String reason,
            boolean exposeDetail) {
    }
}
