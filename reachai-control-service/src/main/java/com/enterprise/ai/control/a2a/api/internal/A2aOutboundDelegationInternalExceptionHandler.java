package com.enterprise.ai.control.a2a.api.internal;

import com.enterprise.ai.control.a2a.domain.A2aDomainException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.time.Instant;
import java.util.Locale;

@RestControllerAdvice(assignableTypes = A2aOutboundDelegationInternalController.class)
public class A2aOutboundDelegationInternalExceptionHandler {

    @ExceptionHandler(A2aDomainException.class)
    public ResponseEntity<ProblemDetail> handle(A2aDomainException failure) {
        HttpStatus status = status(failure.code());
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(status, failure.getMessage());
        detail.setTitle("Outbound A2A delegation rejected");
        detail.setType(URI.create("urn:reachai:a2a-hub:internal-error:"
                + failure.code().toLowerCase(Locale.ROOT).replace('_', '-')));
        detail.setProperty("code", failure.code());
        detail.setProperty("timestamp", Instant.now());
        return ResponseEntity.status(status).body(detail);
    }

    private HttpStatus status(String code) {
        if (code == null) return HttpStatus.INTERNAL_SERVER_ERROR;
        if (code.endsWith("_NOT_FOUND")) {
            return HttpStatus.NOT_FOUND;
        }
        if (code.contains("UNAVAILABLE")) return HttpStatus.SERVICE_UNAVAILABLE;
        if (code.contains("FORBIDDEN") || code.contains("NOT_ALLOWED")
                || code.endsWith("_MISMATCH")) {
            return HttpStatus.FORBIDDEN;
        }
        if (code.endsWith("_CONFLICT") || code.contains("NOT_ACTIVE")
                || code.contains("NOT_CALLABLE") || code.contains("NOT_CONTINUABLE")) {
            return HttpStatus.CONFLICT;
        }
        return HttpStatus.BAD_REQUEST;
    }
}
