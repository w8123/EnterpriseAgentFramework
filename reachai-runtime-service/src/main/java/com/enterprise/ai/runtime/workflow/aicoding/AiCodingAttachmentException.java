package com.enterprise.ai.runtime.workflow.aicoding;

import org.springframework.http.HttpStatus;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public class AiCodingAttachmentException extends RuntimeException {

    private final String code;
    private final HttpStatus status;
    private final Map<String, Object> details;

    public AiCodingAttachmentException(String code, String message) {
        this(code, message, HttpStatus.BAD_REQUEST, Map.of());
    }

    public AiCodingAttachmentException(String code, String message, HttpStatus status) {
        this(code, message, status, Map.of());
    }

    public AiCodingAttachmentException(String code, String message, HttpStatus status, Map<String, Object> details) {
        super(message);
        this.code = code;
        this.status = status == null ? HttpStatus.BAD_REQUEST : status;
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }

    public String code() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }

    public Map<String, Object> details() {
        return details;
    }

    public Map<String, Object> toErrorBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schema", "ai-coding-error.v1");
        body.put("code", code);
        body.put("message", getMessage());
        body.put("details", details);
        body.put("requestId", UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        return body;
    }
}
