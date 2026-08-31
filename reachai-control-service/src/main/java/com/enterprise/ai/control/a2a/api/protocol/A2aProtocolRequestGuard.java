package com.enterprise.ai.control.a2a.api.protocol;

import com.enterprise.ai.control.a2a.application.identity.A2aInboundAuthenticationService;
import com.enterprise.ai.control.a2a.application.identity.A2aInboundCallContext;
import com.enterprise.ai.control.a2a.application.identity.A2aInboundRateLimiter;
import com.enterprise.ai.control.a2a.domain.A2aDomainException;
import com.enterprise.ai.control.a2a.infrastructure.A2aHubProperties;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class A2aProtocolRequestGuard {

    public static final String VERSION_HEADER = "A2A-Version";
    public static final String CALL_CONTEXT_ATTRIBUTE = A2aInboundCallContext.class.getName();

    private final A2aInboundAuthenticationService authenticationService;
    private final A2aInboundRateLimiter rateLimiter;
    private final A2aHubProperties properties;

    public A2aInboundCallContext open(HttpServletRequest request, boolean jsonBodyRequired) {
        requireVersion(request);
        if (jsonBodyRequired) {
            requireJsonContent(request);
        }
        A2aInboundCallContext call = authenticationService.authenticate(
                request.getHeader(HttpHeaders.HOST),
                request.getHeader(A2aInboundAuthenticationService.API_KEY_HEADER));
        long declaredBytes = request.getContentLengthLong();
        if (declaredBytes > call.trustProfile().maxRequestBytes()) {
            throw new A2aDomainException("A2A_REQUEST_TOO_LARGE",
                    "the request exceeds the Trust Profile size limit");
        }
        rateLimiter.requirePermit(call);
        request.setAttribute(CALL_CONTEXT_ATTRIBUTE, call);
        return call;
    }

    private void requireVersion(HttpServletRequest request) {
        String requested = request.getHeader(VERSION_HEADER);
        if (requested == null || requested.isBlank()) {
            requested = request.getParameter(VERSION_HEADER);
        }
        // Per A2A 1.0, a missing/empty version means 0.3, which this interface
        // intentionally does not emulate.
        String interpreted = requested == null || requested.isBlank() ? "0.3" : requested.trim();
        if (!properties.getProtocolVersion().equals(interpreted)) {
            throw new A2aDomainException("A2A_VERSION_NOT_SUPPORTED",
                    "this interface supports A2A-Version " + properties.getProtocolVersion());
        }
    }

    private void requireJsonContent(HttpServletRequest request) {
        String value = request.getContentType();
        if (value == null) {
            throw new A2aDomainException("A2A_CONTENT_TYPE_NOT_SUPPORTED",
                    "Content-Type application/a2a+json is required");
        }
        try {
            MediaType contentType = MediaType.parseMediaType(value);
            MediaType a2a = MediaType.parseMediaType(A2aAgentCardController.A2A_MEDIA_TYPE);
            if (!a2a.isCompatibleWith(contentType)
                    && !MediaType.APPLICATION_JSON.isCompatibleWith(contentType)) {
                throw new A2aDomainException("A2A_CONTENT_TYPE_NOT_SUPPORTED",
                        "Content-Type must be application/a2a+json or application/json");
            }
        } catch (org.springframework.http.InvalidMediaTypeException exception) {
            throw new A2aDomainException("A2A_CONTENT_TYPE_NOT_SUPPORTED",
                    "Content-Type is invalid");
        }
    }
}
