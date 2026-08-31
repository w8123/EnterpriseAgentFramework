package com.enterprise.ai.control.a2a.api.internal;

import com.enterprise.ai.control.a2a.application.outbound.A2aOutboundDelegationContracts.SendRequest;
import com.enterprise.ai.control.a2a.application.outbound.A2aOutboundDelegationContracts.SendResponse;
import com.enterprise.ai.control.a2a.application.outbound.A2aOutboundDelegationService;
import com.enterprise.ai.control.a2a.domain.A2aDomainException;
import com.enterprise.ai.control.internalauth.ControlInternalServiceAuthVerifier;
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

/** HMAC-authenticated Runtime entry point. Exact request bytes are verified before parsing. */
@RestController
@RequestMapping("/internal/control/a2a-hub/delegations")
public class A2aOutboundDelegationInternalController {

    public static final String SEND_PATH =
            "/internal/control/a2a-hub/delegations/message:send";

    private final ControlInternalServiceAuthVerifier authVerifier;
    private final A2aOutboundDelegationService service;
    private final ObjectMapper objectMapper;

    public A2aOutboundDelegationInternalController(
            ControlInternalServiceAuthVerifier authVerifier,
            A2aOutboundDelegationService service,
            ObjectMapper objectMapper) {
        this.authVerifier = authVerifier;
        this.service = service;
        this.objectMapper = objectMapper.copy()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }

    @PostMapping(value = "/message:send", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SendResponse> send(
            HttpServletRequest httpRequest,
            @RequestBody byte[] exactBody) {
        authVerifier.requireRuntime(httpRequest, exactBody);
        SendRequest request;
        try {
            request = objectMapper.readValue(exactBody, SendRequest.class);
        } catch (Exception failure) {
            throw new A2aDomainException("A2A_OUTBOUND_REQUEST_INVALID",
                    "the Runtime outbound delegation request is invalid JSON");
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.send(request));
    }
}
