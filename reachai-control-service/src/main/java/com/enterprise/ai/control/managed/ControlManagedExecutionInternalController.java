package com.enterprise.ai.control.managed;

import com.enterprise.ai.control.internalauth.ControlInternalServiceAuthVerifier;
import com.enterprise.ai.control.managed.ControlManagedExecutionContracts.EventRequest;
import com.enterprise.ai.control.managed.ControlManagedExecutionContracts.EventResponse;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

/** Runtime-only endpoint. HMAC verification always occurs before JSON parsing. */
@RestController
@RequestMapping("/internal/control/managed-executions")
public class ControlManagedExecutionInternalController {

    public static final String EVENTS_PATH = "/internal/control/managed-executions/events";

    private final ControlInternalServiceAuthVerifier authVerifier;
    private final ControlManagedExecutionInboxService service;
    private final ObjectMapper objectMapper;

    public ControlManagedExecutionInternalController(
            ControlInternalServiceAuthVerifier authVerifier,
            ControlManagedExecutionInboxService service,
            ObjectMapper objectMapper) {
        this.authVerifier = authVerifier;
        this.service = service;
        this.objectMapper = objectMapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }

    @PostMapping(value = "/events", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<EventResponse> accept(
            HttpServletRequest httpRequest,
            @RequestBody byte[] exactBody) {
        authVerifier.requireRuntime(httpRequest, exactBody);
        EventRequest request;
        try {
            request = objectMapper.readValue(exactBody, EventRequest.class);
        } catch (Exception invalid) {
            throw new ResponseStatusException(BAD_REQUEST,
                    "Managed Execution event is invalid JSON");
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.accept(request, exactBody));
    }
}
