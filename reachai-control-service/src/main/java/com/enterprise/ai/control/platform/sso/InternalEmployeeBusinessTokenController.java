package com.enterprise.ai.control.platform.sso;

import com.enterprise.ai.control.internalauth.ControlInternalServiceAuthVerifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/** Runtime asks which token to call a business system with for an employee, and reports a refused one. */
@RestController
public class InternalEmployeeBusinessTokenController {

    public static final String RESOLVE_PATH = "/internal/control/employee-business-tokens/resolve";
    public static final String EXPIRED_PATH = "/internal/control/employee-business-tokens/expired";

    private final EmployeeBusinessTokens tokens;
    private final ControlInternalServiceAuthVerifier authVerifier;
    private final ObjectMapper objectMapper;

    public InternalEmployeeBusinessTokenController(EmployeeBusinessTokens tokens,
                                                   ControlInternalServiceAuthVerifier authVerifier,
                                                   ObjectMapper objectMapper) {
        this.tokens = tokens;
        this.authVerifier = authVerifier;
        this.objectMapper = objectMapper;
    }

    @PostMapping(RESOLVE_PATH)
    public ResponseEntity<EmployeeBusinessTokens.Resolution> resolve(HttpServletRequest request,
                                                                     @RequestBody byte[] body) {
        Subject subject = subject(request, body);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(tokens.resolve(subject.tenantId(), subject.projectCode(), subject.externalUserId()));
    }

    @PostMapping(EXPIRED_PATH)
    public Map<String, Object> expired(HttpServletRequest request, @RequestBody byte[] body) {
        Subject subject = subject(request, body);
        tokens.expired(subject.tenantId(), subject.projectCode(), subject.externalUserId());
        return Map.of("forgotten", true);
    }

    private Subject subject(HttpServletRequest request, byte[] body) {
        authVerifier.requireRuntime(request, body);
        try {
            Subject subject = objectMapper.readValue(body, Subject.class);
            if (subject == null || subject.projectCode() == null || subject.externalUserId() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid employee token request");
            }
            return subject;
        } catch (java.io.IOException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid employee token request", invalid);
        }
    }

    public record Subject(String tenantId, String projectCode, String externalUserId) {
    }
}
