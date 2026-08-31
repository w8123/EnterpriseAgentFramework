package com.enterprise.ai.control.mcp.api.protocol;

import com.enterprise.ai.control.mcp.application.protocol.McpProtocolApplicationService;
import com.enterprise.ai.control.mcp.domain.McpDomainException;
import com.enterprise.ai.control.mcp.infrastructure.McpHubProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Enforces the public MCP body cap before Jackson deserializes caller-controlled JSON. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class McpRequestBodyLimitFilter extends OncePerRequestFilter {

    private final McpHubProperties properties;
    private final McpProtocolRequestGuard guard;
    private final McpProtocolApplicationService protocolService;

    public McpRequestBodyLimitFilter(McpHubProperties properties,
                                     McpProtocolRequestGuard guard,
                                     McpProtocolApplicationService protocolService) {
        this.properties = properties;
        this.guard = guard;
        this.protocolService = protocolService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (request == null || !"POST".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = request.getServletPath();
        return !("/mcp".equals(path) || "/mcp/jsonrpc".equals(path));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            guard.open(request);
        } catch (McpDomainException rejected) {
            McpProtocolError category = McpProtocolError.of(rejected);
            reject(request, response, category, rejected.code());
            return;
        }
        int maximum = Math.max(1, properties.getMaxRequestBytes());
        if (request.getContentLengthLong() > maximum) {
            reject(request, response, McpProtocolError.TOO_LARGE, "MCP_REQUEST_TOO_LARGE");
            return;
        }
        int readLimit = maximum == Integer.MAX_VALUE ? Integer.MAX_VALUE : maximum + 1;
        byte[] body = request.getInputStream().readNBytes(readLimit);
        if (body.length > maximum) {
            reject(request, response, McpProtocolError.TOO_LARGE, "MCP_REQUEST_TOO_LARGE");
            return;
        }
        filterChain.doFilter(new CachedBodyRequest(request, body), response);
    }

    private void reject(HttpServletRequest request,
                        HttpServletResponse response,
                        McpProtocolError category,
                        String errorCode) throws IOException {
        response.setStatus(category.httpStatus());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        String body = "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":"
                + category.jsonRpcCode(errorCode) + ",\"message\":\"" + category.safeMessage() + "\"}}";
        response.getWriter().write(body);
        if (category != McpProtocolError.DISABLED) {
            protocolService.audit(new McpProtocolApplicationService.McpAuditRequest(
                    null, "transport", null, false, 0L, category.name(),
                    null, null, errorCode, null, null,
                    request == null ? null : request.getRemoteAddr()));
        }
    }

    private static final class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        private CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body == null ? new byte[0] : body.clone();
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return input.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                    // Synchronous MVC request body consumption only.
                }

                @Override
                public int read() {
                    return input.read();
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }
}
