package com.enterprise.ai.control.a2a.api.management;

import com.enterprise.ai.control.a2a.domain.A2aDomainException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.time.Instant;
import java.util.Locale;

@RestControllerAdvice(assignableTypes = {
        A2aHubOverviewController.class,
        A2aTrustProfileController.class,
        A2aPublicationController.class,
        A2aCredentialController.class,
        A2aPrincipalController.class,
        A2aTaskManagementController.class,
        A2aRemoteAgentController.class
})
public class A2aHubManagementExceptionHandler {

    @ExceptionHandler(A2aDomainException.class)
    public ResponseEntity<ProblemDetail> handle(A2aDomainException exception) {
        HttpStatus status = status(exception.code());
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(status, exception.getMessage());
        detail.setTitle("A2A Hub request failed");
        detail.setType(URI.create("urn:reachai:a2a-hub:error:"
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
