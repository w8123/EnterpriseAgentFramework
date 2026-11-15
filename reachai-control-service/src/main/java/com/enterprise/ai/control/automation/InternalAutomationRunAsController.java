package com.enterprise.ai.control.automation;

import com.enterprise.ai.control.internalauth.ControlInternalServiceAuthVerifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Runtime asks, before each scheduled run, whose business identity the Automation may act as. */
@RestController
public class InternalAutomationRunAsController {

    public static final String PATH = "/internal/control/automation-run-as/resolve";

    private final AutomationRunAsResolver resolver;
    private final ControlInternalServiceAuthVerifier authVerifier;
    private final ObjectMapper objectMapper;

    public InternalAutomationRunAsController(AutomationRunAsResolver resolver,
                                             ControlInternalServiceAuthVerifier authVerifier,
                                             ObjectMapper objectMapper) {
        this.resolver = resolver;
        this.authVerifier = authVerifier;
        this.objectMapper = objectMapper;
    }

    @PostMapping(PATH)
    public ResponseEntity<AutomationRunAsResolver.Resolution> resolve(HttpServletRequest request,
                                                                      @RequestBody byte[] body) {
        authVerifier.requireRuntime(request, body);
        AutomationRunAsResolver.Request parsed;
        try {
            parsed = objectMapper.readValue(body, AutomationRunAsResolver.Request.class);
        } catch (java.io.IOException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid automation run-as request", invalid);
        }
        return ResponseEntity.ok(resolver.resolve(parsed));
    }
}
