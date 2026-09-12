package com.enterprise.ai.control.aiassist;

import org.springframework.http.HttpStatus;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public class ControlAiCodingAgentException extends RuntimeException {

    private final String code;
    private final HttpStatus status;
    private final Map<String, Object> details;

    public ControlAiCodingAgentException(
            String code,
            String message,
            HttpStatus status,
            Map<String, Object> details) {
        super(message);
        this.code = code;
        this.status = status;
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }

    public ControlAiCodingAgentException(String code, String message, HttpStatus status) {
        this(code, message, status, Map.of());
    }

    public HttpStatus status() {
        return status;
    }

    public Map<String, Object> toBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schema", "agent-ai-coding-error.v1");
        body.put("code", code);
        body.put("message", getMessage());
        body.put("details", details);
        body.put("requestId", UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        return body;
    }
}
