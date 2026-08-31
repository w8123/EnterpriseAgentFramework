package com.enterprise.ai.control.mcp.api.protocol;

import com.enterprise.ai.control.mcp.domain.McpDomainException;
import com.enterprise.ai.control.mcp.infrastructure.McpHubProperties;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.Locale;
import java.util.Set;

/**
 * Inbound guard for the public MCP JSON-RPC surface: body size cap, method
 * whitelist, JSON-RPC 2.0 structural checks, and Authorization hygiene.
 * Credentials are never echoed into logs or error messages.
 */
@Component
@RequiredArgsConstructor
public class McpProtocolRequestGuard {

    public static final String PROTOCOL_VERSION_HEADER = "MCP-Protocol-Version";
    public static final String METHOD_HEADER = "Mcp-Method";
    public static final String NAME_HEADER = "Mcp-Name";
    public static final String MODERN_PROTOCOL_META = "io.modelcontextprotocol/protocolVersion";

    public static final Set<String> ALLOWED_METHODS = Set.of(
            "initialize", "notifications/initialized", "server/discover", "tools/list", "tools/call");

    private static final String BEARER_PREFIX = "Bearer ";

    private final McpHubProperties properties;

    /** Enforces the body cap before the JSON payload is trusted. */
    public void open(HttpServletRequest request) {
        if (request == null) {
            throw protocolError("MCP_REQUEST_INVALID", "request is required");
        }
        if (!properties.isEnabled()) {
            throw protocolError("MCP_PROTOCOL_DISABLED", "the MCP protocol surface is disabled");
        }
        long declaredBytes = request.getContentLengthLong();
        if (declaredBytes > properties.getMaxRequestBytes()) {
            throw protocolError("MCP_REQUEST_TOO_LARGE",
                    "the request exceeds the MCP body size limit");
        }
        validateOrigin(request.getHeader(HttpHeaders.ORIGIN));
        requireJsonContent(request.getContentType());
    }

    /** Extracts the Bearer token without ever returning it to callers that log. */
    public String bearerToken(HttpServletRequest request) {
        String header = request == null ? null : request.getHeader(HttpHeaders.AUTHORIZATION);
        if (!StringUtils.hasText(header) || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }

    /** Structural JSON-RPC 2.0 validation plus the MCP method whitelist. */
    public ValidatedRequest validate(HttpServletRequest httpRequest, Map<String, Object> body) {
        if (body == null) {
            throw protocolError("MCP_REQUEST_INVALID", "a JSON-RPC 2.0 request body is required");
        }
        if (!"2.0".equals(body.get("jsonrpc"))) {
            throw protocolError("MCP_REQUEST_INVALID", "jsonrpc must be \"2.0\"");
        }
        Object id = body.get("id");
        if (id != null && !(id instanceof String || id instanceof Number)) {
            throw protocolError("MCP_REQUEST_INVALID", "id must be a string, a number, or null");
        }
        Object method = body.get("method");
        if (!(method instanceof String text) || text.isBlank()) {
            throw protocolError("MCP_REQUEST_INVALID", "method is required");
        }
        String normalized = text.trim();
        if (!ALLOWED_METHODS.contains(normalized)) {
            throw protocolError("MCP_METHOD_NOT_FOUND", "method not found: " + normalized);
        }
        Map<String, Object> params = params(body.get("params"));
        ProtocolMode mode = protocolMode(httpRequest, normalized, params);
        boolean notification = normalized.startsWith("notifications/");
        if (notification && id != null) {
            throw protocolError("MCP_REQUEST_INVALID", "notifications must not include an id");
        }
        if (!notification && id == null) {
            throw protocolError("MCP_REQUEST_INVALID", "requests must include an id");
        }
        validateAccept(httpRequest == null ? null : httpRequest.getHeader(HttpHeaders.ACCEPT), mode);
        if (mode == ProtocolMode.MODERN) {
            validateModernEnvelope(httpRequest, normalized, params);
        }
        return new ValidatedRequest(id, normalized, params, mode, notification);
    }

    private ProtocolMode protocolMode(HttpServletRequest request, String method,
                                      Map<String, Object> params) {
        String headerVersion = text(request == null ? null : request.getHeader(PROTOCOL_VERSION_HEADER));
        if ("initialize".equals(method)) {
            String requested = text(params.get("protocolVersion"));
            if (!properties.getLegacyProtocolVersion().equals(requested)) {
                throw protocolError("MCP_PROTOCOL_VERSION_UNSUPPORTED",
                        "unsupported MCP protocol version");
            }
            if (headerVersion != null && !headerVersion.equals(requested)) {
                throw protocolError("MCP_PROTOCOL_HEADER_MISMATCH",
                        "MCP-Protocol-Version does not match params.protocolVersion");
            }
            return ProtocolMode.LEGACY;
        }
        if ("server/discover".equals(method)) {
            requireVersion(headerVersion, properties.getModernProtocolVersion());
            return ProtocolMode.MODERN;
        }
        if (properties.getLegacyProtocolVersion().equals(headerVersion)) {
            return ProtocolMode.LEGACY;
        }
        if (properties.getModernProtocolVersion().equals(headerVersion)) {
            return ProtocolMode.MODERN;
        }
        throw protocolError("MCP_PROTOCOL_VERSION_UNSUPPORTED",
                "a supported MCP-Protocol-Version header is required");
    }

    private void validateModernEnvelope(HttpServletRequest request, String method,
                                        Map<String, Object> params) {
        String headerMethod = text(request == null ? null : request.getHeader(METHOD_HEADER));
        if (!method.equals(headerMethod)) {
            throw protocolError("MCP_PROTOCOL_HEADER_MISMATCH",
                    "Mcp-Method does not match the JSON-RPC method");
        }
        String bodyName = "tools/call".equals(method) ? text(params.get("name")) : null;
        String headerName = text(request == null ? null : request.getHeader(NAME_HEADER));
        if (bodyName != null && !bodyName.equals(headerName)) {
            throw protocolError("MCP_PROTOCOL_HEADER_MISMATCH",
                    "Mcp-Name does not match params.name");
        }
        Map<String, Object> meta = params(params.get("_meta"));
        String metaVersion = text(meta.get(MODERN_PROTOCOL_META));
        requireVersion(metaVersion, properties.getModernProtocolVersion());
    }

    private void requireVersion(String actual, String expected) {
        if (!expected.equals(actual)) {
            throw protocolError("MCP_PROTOCOL_VERSION_UNSUPPORTED",
                    "unsupported MCP protocol version");
        }
    }

    private void validateAccept(String acceptHeader, ProtocolMode mode) {
        String accept = acceptHeader == null ? "" : acceptHeader.toLowerCase(Locale.ROOT);
        boolean json = accept.contains(MediaType.APPLICATION_JSON_VALUE) || accept.contains("*/*");
        boolean eventStream = accept.contains(MediaType.TEXT_EVENT_STREAM_VALUE) || accept.contains("*/*");
        if (!json || (mode == ProtocolMode.LEGACY && !eventStream)) {
            throw protocolError("MCP_ACCEPT_NOT_SUPPORTED",
                    mode == ProtocolMode.LEGACY
                            ? "Accept must include application/json and text/event-stream"
                            : "Accept must include application/json");
        }
    }

    private void validateOrigin(String origin) {
        String normalized = text(origin);
        if (normalized == null) {
            return;
        }
        boolean allowed = properties.getAllowedOrigins().stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .anyMatch(normalized::equals);
        if (!allowed) {
            throw protocolError("MCP_ORIGIN_FORBIDDEN", "the request Origin is not allowed");
        }
    }

    private void requireJsonContent(String contentType) {
        if (contentType == null) {
            throw protocolError("MCP_CONTENT_TYPE_NOT_SUPPORTED",
                    "Content-Type application/json is required");
        }
        try {
            MediaType parsed = MediaType.parseMediaType(contentType);
            if (!MediaType.APPLICATION_JSON.isCompatibleWith(parsed)) {
                throw protocolError("MCP_CONTENT_TYPE_NOT_SUPPORTED",
                        "Content-Type must be application/json");
            }
        } catch (org.springframework.http.InvalidMediaTypeException exception) {
            throw protocolError("MCP_CONTENT_TYPE_NOT_SUPPORTED", "Content-Type is invalid");
        }
    }

    private Map<String, Object> params(Object value) {
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> raw)) {
            throw protocolError("MCP_REQUEST_INVALID", "params must be an object");
        }
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        raw.forEach((key, item) -> {
            if (key != null) {
                // JSON null is a valid parameter value. Collectors.toMap rejects
                // null values and would turn a valid request into an uncaught NPE.
                result.put(String.valueOf(key), item);
            }
        });
        return result;
    }

    private static McpDomainException protocolError(String code, String message) {
        return new McpDomainException(code, message);
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String normalized = String.valueOf(value).trim();
        return normalized.isEmpty() ? null : normalized;
    }

    public enum ProtocolMode {
        LEGACY,
        MODERN
    }

    public record ValidatedRequest(Object id, String method, Map<String, Object> params,
                                   ProtocolMode mode, boolean notification) {
    }
}
