package com.enterprise.ai.control.a2a.api.protocol;

import com.enterprise.ai.control.a2a.application.identity.A2aInboundCallContext;
import com.enterprise.ai.control.a2a.application.port.A2aTransportAuditRepository;
import com.enterprise.ai.control.a2a.application.port.A2aTransportAuditRepository.TransportAuditRecord;
import com.enterprise.ai.control.a2a.infrastructure.A2aHubProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.UUID;

import static com.enterprise.ai.control.a2a.domain.A2aProtocolOperation.MESSAGE_SEND;
import static com.enterprise.ai.control.a2a.domain.A2aProtocolOperation.TASKS_CANCEL;
import static com.enterprise.ai.control.a2a.domain.A2aProtocolOperation.TASKS_GET;
import static com.enterprise.ai.control.a2a.domain.A2aProtocolOperation.TASKS_LIST;

/** Writes body-free, credential-free A2A transport evidence after every protocol request. */
@Component
@Slf4j
@Order(Ordered.LOWEST_PRECEDENCE - 20)
@ConditionalOnProperty(prefix = "reachai.a2a-hub", name = "enabled", havingValue = "true")
public class A2aTransportAuditFilter extends OncePerRequestFilter {

    private final A2aTransportAuditRepository repository;
    private final A2aHubProperties properties;
    private final Clock clock;

    public A2aTransportAuditFilter(
            A2aTransportAuditRepository repository,
            A2aHubProperties properties,
            Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request == null ? null : request.getRequestURI();
        String context = request == null ? null : request.getContextPath();
        if (uri != null && context != null && !context.isEmpty() && uri.startsWith(context)) {
            uri = uri.substring(context.length());
        }
        return uri == null || !uri.startsWith("/a2a/v1/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        long started = System.nanoTime();
        try {
            filterChain.doFilter(request, response);
        } finally {
            long latencyMs = Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
            try {
                save(request, response, latencyMs);
            } catch (RuntimeException auditFailure) {
                // Protocol availability must not be changed after a response was already produced.
                log.warn("[A2ATransportAudit] write failed: {}", safe(auditFailure.getMessage()));
            }
        }
    }

    private void save(HttpServletRequest request, HttpServletResponse response, long latencyMs) {
        Object raw = request.getAttribute(A2aProtocolRequestGuard.CALL_CONTEXT_ATTRIBUTE);
        A2aInboundCallContext call = raw instanceof A2aInboundCallContext value ? value : null;
        int status = response.getStatus();
        long declared = request.getContentLengthLong();
        repository.save(new TransportAuditRecord(
                "req_" + UUID.randomUUID().toString().replace("-", ""),
                "INBOUND",
                call == null ? null : call.principal().principalId(),
                call == null ? null : call.publication().publicationId(),
                null,
                null,
                "HTTP+JSON",
                properties.getProtocolVersion(),
                operation(request),
                status >= 200 && status < 300,
                status,
                attribute(request, A2aProtocolExceptionHandler.ERROR_CODE_ATTRIBUTE),
                latencyMs,
                declared < 0 ? null : declared,
                responseBytes(response),
                null,
                null,
                call == null ? "{\"authenticated\":false}" : "{\"authenticated\":true}",
                "{\"httpStatus\":" + status + "}",
                pseudonymize(request.getRemoteAddr()),
                status >= 400 ? "A2A protocol request failed" : null,
                null,
                LocalDateTime.now(clock)));
    }

    private String operation(HttpServletRequest request) {
        String method = request.getMethod();
        String path = request.getRequestURI();
        if (path.endsWith("/message:send")) return MESSAGE_SEND.wireName();
        if (path.endsWith("/message:stream")) return "message:stream";
        if (path.endsWith(":cancel")) return TASKS_CANCEL.wireName();
        if (path.endsWith(":subscribe")) return "task:subscribe";
        if (path.endsWith("/tasks")) return TASKS_LIST.wireName();
        if (path.contains("/pushNotificationConfigs")) return "push-config";
        if (path.contains("/tasks/")) return TASKS_GET.wireName();
        if (path.endsWith("/extendedAgentCard")) return "extended-card:get";
        return (method == null ? "UNKNOWN" : method.toUpperCase()) + ":unknown";
    }

    private Long responseBytes(HttpServletResponse response) {
        String value = response.getHeader("Content-Length");
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            long parsed = Long.parseLong(value);
            return parsed < 0 ? null : parsed;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String pseudonymize(String remoteAddress) {
        String key = properties.getAuditIpHashKey();
        if (key == null || key.isBlank() || remoteAddress == null || remoteAddress.isBlank()) {
            return null;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(remoteAddress.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception unavailable) {
            throw new IllegalStateException("A2A remote-address pseudonymization failed", unavailable);
        }
    }

    private String attribute(HttpServletRequest request, String name) {
        Object value = request.getAttribute(name);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text.substring(0, Math.min(text.length(), 96));
    }

    private String safe(String value) {
        if (value == null || value.isBlank()) {
            return "A2A transport audit failed";
        }
        String normalized = value.replaceAll("[\\r\\n\\t]+", " ").trim();
        return normalized.length() <= 500 ? normalized : normalized.substring(0, 500);
    }
}
