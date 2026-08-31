package com.enterprise.ai.control.mcp.api.management;

import com.enterprise.ai.control.mcp.domain.McpDomainException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.time.Instant;
import java.util.Locale;

@RestControllerAdvice(assignableTypes = {
        McpHubOverviewController.class,
        McpCallLogController.class,
        McpPublicationController.class,
        McpClientController.class
})
public class McpHubManagementExceptionHandler {

    @ExceptionHandler(McpDomainException.class)
    public ResponseEntity<ProblemDetail> handle(McpDomainException exception) {
        HttpStatus status = status(exception.code());
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(status, exception.getMessage());
        detail.setTitle("MCP Hub request failed");
        detail.setType(URI.create("urn:reachai:mcp-hub:error:"
                + exception.code().toLowerCase(Locale.ROOT).replace('_', '-')));
        detail.setProperty("code", exception.code());
        detail.setProperty("timestamp", Instant.now());
        return ResponseEntity.status(status).body(detail);
    }

    private HttpStatus status(String code) {
        if (code != null && code.endsWith("_NOT_FOUND")) {
            return HttpStatus.NOT_FOUND;
        }
        if (code != null && (code.endsWith("_CONFLICT") || code.endsWith("_ARCHIVED"))) {
            return HttpStatus.CONFLICT;
        }
        return HttpStatus.BAD_REQUEST;
    }
}
