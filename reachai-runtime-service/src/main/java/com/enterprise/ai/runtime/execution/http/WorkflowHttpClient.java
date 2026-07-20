package com.enterprise.ai.runtime.execution.http;

import com.enterprise.ai.runtime.credential.RuntimeWorkflowCredentialRuntime;
import com.enterprise.ai.runtime.credential.RuntimeWorkflowCredentialService;
import com.enterprise.ai.runtime.credential.WorkflowCredentialTypes;
import com.enterprise.ai.runtime.execution.identity.WorkflowExecutionIdentity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Outbound HTTP client for Workflow HTTP_REQUEST with egress checks, credential injection,
 * same-origin redirect policy, response size limits and Trace redaction.
 */
@Component
public class WorkflowHttpClient {

    public static final int DEFAULT_TIMEOUT_MS = 30_000;
    public static final int MIN_TIMEOUT_MS = 1_000;
    public static final int MAX_TIMEOUT_MS = 120_000;
    public static final int MAX_RESPONSE_BYTES = 1_048_576;

    private static final Set<Integer> RETRYABLE_STATUS = Set.of(408, 429, 500, 502, 503, 504);
    private static final Set<String> PROTECTED_HEADERS = Set.of(
            "host",
            "content-length",
            "transfer-encoding",
            "connection",
            "proxy-authorization",
            "keep-alive",
            "upgrade",
            "te",
            "trailer");

    private final ObjectMapper objectMapper;
    private final WorkflowHttpEgressPolicy egressPolicy;
    private final RuntimeWorkflowCredentialService credentialService;

    public WorkflowHttpClient(ObjectMapper objectMapper,
                              WorkflowHttpEgressPolicy egressPolicy,
                              RuntimeWorkflowCredentialService credentialService) {
        this.objectMapper = objectMapper;
        this.egressPolicy = egressPolicy == null ? WorkflowHttpEgressPolicy.productionDefault() : egressPolicy;
        this.credentialService = credentialService;
    }

    public HttpExecutionResult execute(HttpExecutionRequest request) {
        long started = System.currentTimeMillis();
        try {
            String method = normalizeMethod(request.method());
            int timeoutMs = clampTimeout(request.timeoutMs());
            Map<String, String> headers = new LinkedHashMap<>();
            if (request.headers() != null) {
                request.headers().forEach((key, value) -> {
                    if (StringUtils.hasText(key) && value != null) {
                        rejectCrLf(key, "headerName");
                        rejectCrLf(value, "headerValue");
                        rejectProtectedHeader(key);
                        headers.put(key, value);
                    }
                });
            }
            Map<String, String> queryParams = new LinkedHashMap<>();
            if (request.queryParams() != null) {
                request.queryParams().forEach((key, value) -> {
                    if (StringUtils.hasText(key)) {
                        rejectCrLf(key, "queryParam");
                        queryParams.put(key, value == null ? "" : value);
                    }
                });
            }
            CredentialInjection credential = injectCredential(headers, queryParams, request);
            String url = appendQuery(request.url(), queryParams);
            ResolvedTarget target = resolveAndValidate(url, false);

            int redirects = 0;
            String currentUrl = url;
            ResolvedTarget currentTarget = target;
            Map<String, String> currentHeaders = new LinkedHashMap<>(headers);
            while (true) {
                HttpURLConnection connection = null;
                try {
                    connection = openPinnedConnection(currentTarget, timeoutMs);
                    connection.setInstanceFollowRedirects(false);
                    connection.setRequestMethod(method);
                    connection.setConnectTimeout(timeoutMs);
                    connection.setReadTimeout(timeoutMs);
                    currentHeaders.forEach(connection::setRequestProperty);
                    byte[] bodyBytes = encodeBody(request.bodyType(), request.body());
                    if (bodyBytes != null && bodyBytes.length > 0
                            && !"GET".equals(method) && !"HEAD".equals(method)) {
                        connection.setDoOutput(true);
                        try (OutputStream output = connection.getOutputStream()) {
                            output.write(bodyBytes);
                        }
                    }
                    int status = connection.getResponseCode();
                    if (isRedirect(status) && redirects < egressPolicy.maxRedirects()) {
                        String location = connection.getHeaderField("Location");
                        if (!StringUtils.hasText(location)) {
                            throw new WorkflowHttpExecutionException(
                                    "RUNTIME_HTTP_REDIRECT_INVALID",
                                    "HTTP redirect missing Location header");
                        }
                        URI nextUri = URI.create(currentUrl).resolve(location.trim());
                        String nextUrl = nextUri.toString();
                        boolean sameOrigin = sameOrigin(currentTarget.uri(), nextUri);
                        boolean credentialed = credential.credentialed();
                        if (credentialed && !sameOrigin) {
                            throw new WorkflowHttpExecutionException(
                                    "RUNTIME_HTTP_REDIRECT_CREDENTIAL_DENIED",
                                    "Credentialed HTTP redirect to different origin is denied");
                        }
                        if (isHttpsToHttpDowngrade(currentTarget.uri(), nextUri)) {
                            throw new WorkflowHttpExecutionException(
                                    "RUNTIME_HTTP_REDIRECT_DOWNGRADE_DENIED",
                                    "HTTPS to HTTP redirect is denied");
                        }
                        ResolvedTarget nextTarget = resolveAndValidate(nextUrl, true);
                        currentUrl = nextUrl;
                        currentTarget = nextTarget;
                        if (credentialed) {
                            // same-origin only: keep credentials; still revalidate egress above
                            currentHeaders = new LinkedHashMap<>(headers);
                        } else {
                            currentHeaders = stripCredentialHeaders(headers, credential.sensitiveHeaderNames());
                        }
                        redirects++;
                        connection.disconnect();
                        continue;
                    }
                    if (isRedirect(status)) {
                        throw new WorkflowHttpExecutionException(
                                WorkflowHttpEgressPolicy.ERROR_REDIRECT,
                                "HTTP redirect limit exceeded");
                    }
                    byte[] responseBytes = readBounded(status >= 400
                            ? connection.getErrorStream()
                            : connection.getInputStream());
                    String contentType = connection.getContentType();
                    String bodyText = responseBytes == null
                            ? ""
                            : new String(responseBytes, StandardCharsets.UTF_8);
                    Object parsedBody = tryParseJson(contentType, bodyText);
                    Map<String, List<String>> responseHeaders = redactHeaders(
                            connection.getHeaderFields(), credential.sensitiveHeaderNames());
                    boolean success = status >= 200 && status < 300;
                    String code = success
                            ? "RUNTIME_HTTP_EXECUTED"
                            : ("RUNTIME_HTTP_STATUS_" + status);
                    return new HttpExecutionResult(
                            success,
                            code,
                            status,
                            responseHeaders,
                            bodyText,
                            parsedBody,
                            contentType,
                            System.currentTimeMillis() - started,
                            responseBytes == null ? 0 : responseBytes.length,
                            redirects,
                            safeUrlSummary(currentUrl),
                            isRetryableStatus(status));
                } finally {
                    if (connection != null) {
                        connection.disconnect();
                    }
                }
            }
        } catch (WorkflowHttpEgressPolicy.WorkflowHttpEgressException ex) {
            return failure(ex.code(), ex.getMessage(), started, request == null ? null : request.url(), false);
        } catch (WorkflowHttpExecutionException ex) {
            boolean retryable = isRetryableFailureCode(ex.code());
            return failure(ex.code(), ex.getMessage(), started, request == null ? null : request.url(), retryable);
        } catch (java.net.SocketTimeoutException ex) {
            return failure("RUNTIME_HTTP_TIMEOUT", "HTTP request timed out", started,
                    request == null ? null : request.url(), true);
        } catch (java.net.ConnectException ex) {
            return failure("RUNTIME_HTTP_CONNECT_FAILED", "HTTP connection failed", started,
                    request == null ? null : request.url(), true);
        } catch (Exception ex) {
            return failure("RUNTIME_HTTP_FAILED",
                    "HTTP request failed: " + safeMessage(ex),
                    started,
                    request == null ? null : request.url(),
                    false);
        }
    }

    public boolean isIdempotentMethod(String method) {
        try {
            String normalized = normalizeMethod(method);
            return "GET".equals(normalized) || "HEAD".equals(normalized) || "OPTIONS".equals(normalized);
        } catch (WorkflowHttpExecutionException ex) {
            return false;
        }
    }

    public boolean isRetryableStatus(int status) {
        return RETRYABLE_STATUS.contains(status);
    }

    private CredentialInjection injectCredential(Map<String, String> headers,
                                                 Map<String, String> queryParams,
                                                 HttpExecutionRequest request) {
        if (!StringUtils.hasText(request.credentialRef())) {
            return CredentialInjection.none();
        }
        if (credentialService == null) {
            throw new WorkflowHttpExecutionException(
                    "RUNTIME_HTTP_CREDENTIAL_UNAVAILABLE",
                    "Credential service is unavailable");
        }
        WorkflowExecutionIdentity identity = request.identity() == null
                ? WorkflowExecutionIdentity.untrustedDebug()
                : request.identity();
        Optional<RuntimeWorkflowCredentialRuntime> resolved = credentialService.resolve(
                request.credentialRef(), identity);
        if (resolved.isEmpty()) {
            throw new WorkflowHttpExecutionException(
                    "RUNTIME_HTTP_CREDENTIAL_DENIED",
                    "credentialRef not found, disabled, or out of scope: " + request.credentialRef().trim());
        }
        RuntimeWorkflowCredentialRuntime credential = resolved.get();
        Map<String, Object> secret = credential.secret() == null ? Map.of() : credential.secret();
        String type = WorkflowCredentialTypes.normalizeType(credential.type());
        Set<String> sensitive = WorkflowCredentialTypes.sensitiveHeaderNames(secret, type);
        switch (type) {
            case WorkflowCredentialTypes.BEARER -> {
                String token = firstText(secret, "token");
                headers.put("Authorization", "Bearer " + token);
            }
            case WorkflowCredentialTypes.API_KEY_HEADER -> {
                String headerName = firstText(secret, "headerName");
                String headerValue = firstText(secret, "apiKey");
                headers.put(headerName, headerValue);
                sensitive.add(headerName.toLowerCase(Locale.ROOT));
            }
            case WorkflowCredentialTypes.API_KEY_QUERY -> {
                String paramName = firstText(secret, "paramName");
                String apiKey = firstText(secret, "apiKey");
                // Duplicate paramName: credential wins (overwrite).
                queryParams.put(paramName, apiKey);
            }
            case WorkflowCredentialTypes.BASIC -> {
                String username = firstText(secret, "username");
                String password = firstText(secret, "password");
                String encoded = java.util.Base64.getEncoder().encodeToString(
                        (username + ":" + (password == null ? "" : password)).getBytes(StandardCharsets.UTF_8));
                headers.put("Authorization", "Basic " + encoded);
            }
            case WorkflowCredentialTypes.CUSTOM_HEADERS -> {
                Object headersRaw = secret.get("headers");
                if (headersRaw instanceof Map<?, ?> map) {
                    map.forEach((key, value) -> {
                        if (key != null && value != null) {
                            String name = String.valueOf(key);
                            headers.put(name, String.valueOf(value));
                            sensitive.add(name.toLowerCase(Locale.ROOT));
                        }
                    });
                }
            }
            default -> throw new WorkflowHttpExecutionException(
                    "RUNTIME_HTTP_CREDENTIAL_TYPE_UNSUPPORTED",
                    "Unsupported credential type for HTTP_REQUEST: " + type);
        }
        return new CredentialInjection(true, sensitive);
    }

    private ResolvedTarget resolveAndValidate(String rawUri, boolean redirect) {
        // Single DNS resolution per hop: egress validation returns the pinned address set.
        WorkflowHttpEgressPolicy.ResolvedHost resolved = redirect
                ? egressPolicy.validateRedirectTarget(rawUri)
                : egressPolicy.validateUri(rawUri);
        return new ResolvedTarget(resolved.uri(), resolved.primaryAddress());
    }

    private HttpURLConnection openPinnedConnection(ResolvedTarget target, int timeoutMs) throws Exception {
        URI uri = target.uri();
        String scheme = uri.getScheme() == null ? "http" : uri.getScheme().toLowerCase(Locale.ROOT);
        int port = uri.getPort() > 0 ? uri.getPort() : ("https".equals(scheme) ? 443 : 80);
        String file = buildFile(uri);
        URL url = new URL(scheme, target.address().getHostAddress(), port, file);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection(Proxy.NO_PROXY);
        String hostHeader = uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
        connection.setRequestProperty("Host", hostHeader);
        if (connection instanceof HttpsURLConnection https) {
            SSLSocketFactory base = https.getSSLSocketFactory();
            https.setSSLSocketFactory(new SniPinningSslSocketFactory(base, uri.getHost(), target.address(), port));
            https.setHostnameVerifier((hostname, session) ->
                    HttpsURLConnection.getDefaultHostnameVerifier().verify(uri.getHost(), session));
        }
        connection.setConnectTimeout(timeoutMs);
        connection.setReadTimeout(timeoutMs);
        return connection;
    }

    private static String buildFile(URI uri) {
        String path = uri.getRawPath() == null || uri.getRawPath().isBlank() ? "/" : uri.getRawPath();
        String query = uri.getRawQuery();
        return query == null ? path : path + "?" + query;
    }

    private String appendQuery(String url, Map<String, String> queryParams) {
        if (!StringUtils.hasText(url)) {
            throw new WorkflowHttpExecutionException("RUNTIME_HTTP_URL_REQUIRED", "HTTP URL is required");
        }
        if (queryParams == null || queryParams.isEmpty()) {
            return url.trim();
        }
        try {
            URI uri = URI.create(url.trim());
            Map<String, String> merged = new LinkedHashMap<>();
            if (StringUtils.hasText(uri.getRawQuery())) {
                for (String pair : uri.getRawQuery().split("&")) {
                    if (!StringUtils.hasText(pair)) {
                        continue;
                    }
                    int idx = pair.indexOf('=');
                    String key = idx >= 0 ? pair.substring(0, idx) : pair;
                    String value = idx >= 0 ? pair.substring(idx + 1) : "";
                    merged.put(decode(key), decode(value));
                }
            }
            // Explicit / credential params overwrite duplicates deterministically.
            merged.putAll(queryParams);
            StringBuilder query = new StringBuilder();
            for (Map.Entry<String, String> entry : merged.entrySet()) {
                if (!StringUtils.hasText(entry.getKey())) {
                    continue;
                }
                if (!query.isEmpty()) {
                    query.append('&');
                }
                query.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8));
                query.append('=');
                query.append(URLEncoder.encode(entry.getValue() == null ? "" : entry.getValue(), StandardCharsets.UTF_8));
            }
            String path = uri.getRawPath() == null ? "" : uri.getRawPath();
            return new URI(uri.getScheme(), uri.getAuthority(), path, query.toString(), uri.getFragment()).toString();
        } catch (WorkflowHttpExecutionException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new WorkflowHttpExecutionException("RUNTIME_HTTP_URL_INVALID", "HTTP URL is invalid");
        }
    }

    private byte[] encodeBody(String bodyType, String body) {
        String type = bodyType == null ? "none" : bodyType.trim().toLowerCase(Locale.ROOT);
        if ("none".equals(type) || !StringUtils.hasText(body)) {
            return null;
        }
        return body.getBytes(StandardCharsets.UTF_8);
    }

    private byte[] readBounded(InputStream inputStream) throws Exception {
        if (inputStream == null) {
            return new byte[0];
        }
        try (InputStream in = inputStream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = in.read(buffer)) >= 0) {
                total += read;
                if (total > MAX_RESPONSE_BYTES) {
                    throw new WorkflowHttpExecutionException(
                            "RUNTIME_HTTP_RESPONSE_TOO_LARGE",
                            "HTTP response exceeded max size of " + MAX_RESPONSE_BYTES + " bytes");
                }
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    private Object tryParseJson(String contentType, String body) {
        if (!StringUtils.hasText(body)) {
            return null;
        }
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        boolean looksJson = type.contains("json") || body.trim().startsWith("{") || body.trim().startsWith("[");
        if (!looksJson) {
            return null;
        }
        try {
            return objectMapper.readValue(body, Object.class);
        } catch (Exception ex) {
            return null;
        }
    }

    private Map<String, List<String>> redactHeaders(Map<String, List<String>> headers, Set<String> sensitive) {
        Map<String, List<String>> redacted = new LinkedHashMap<>();
        if (headers == null) {
            return redacted;
        }
        headers.forEach((key, values) -> {
            if (key == null) {
                return;
            }
            String lower = key.toLowerCase(Locale.ROOT);
            if (sensitive.contains(lower)) {
                redacted.put(key, List.of("***"));
            } else {
                redacted.put(key, values == null ? List.of() : new ArrayList<>(values));
            }
        });
        return redacted;
    }

    private Map<String, String> stripCredentialHeaders(Map<String, String> headers, Set<String> sensitive) {
        Map<String, String> out = new LinkedHashMap<>();
        headers.forEach((key, value) -> {
            if (key != null && !sensitive.contains(key.toLowerCase(Locale.ROOT))) {
                out.put(key, value);
            }
        });
        return out;
    }

    private HttpExecutionResult failure(String code, String message, long started, String url, boolean retryable) {
        return new HttpExecutionResult(
                false,
                code,
                0,
                Map.of(),
                message,
                null,
                null,
                System.currentTimeMillis() - started,
                0,
                0,
                safeUrlSummary(url),
                retryable);
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static boolean sameOrigin(URI left, URI right) {
        if (left == null || right == null) {
            return false;
        }
        String leftScheme = left.getScheme() == null ? "" : left.getScheme().toLowerCase(Locale.ROOT);
        String rightScheme = right.getScheme() == null ? "" : right.getScheme().toLowerCase(Locale.ROOT);
        String leftHost = left.getHost() == null ? "" : left.getHost().toLowerCase(Locale.ROOT);
        String rightHost = right.getHost() == null ? "" : right.getHost().toLowerCase(Locale.ROOT);
        return leftScheme.equals(rightScheme)
                && leftHost.equals(rightHost)
                && effectivePort(left) == effectivePort(right);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() > 0) {
            return uri.getPort();
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        return "https".equals(scheme) ? 443 : 80;
    }

    static boolean isHttpsToHttpDowngrade(URI from, URI to) {
        String fromScheme = from.getScheme() == null ? "" : from.getScheme().toLowerCase(Locale.ROOT);
        String toScheme = to.getScheme() == null ? "" : to.getScheme().toLowerCase(Locale.ROOT);
        return "https".equals(fromScheme) && "http".equals(toScheme);
    }

    private static boolean isRetryableFailureCode(String code) {
        if (!StringUtils.hasText(code)) {
            return false;
        }
        return "RUNTIME_HTTP_TIMEOUT".equals(code)
                || "RUNTIME_HTTP_CONNECT_FAILED".equals(code)
                || code.startsWith("RUNTIME_HTTP_STATUS_5")
                || "RUNTIME_HTTP_STATUS_408".equals(code)
                || "RUNTIME_HTTP_STATUS_429".equals(code);
    }

    private static int clampTimeout(Integer timeoutMs) {
        int value = timeoutMs == null ? DEFAULT_TIMEOUT_MS : timeoutMs;
        return Math.max(MIN_TIMEOUT_MS, Math.min(MAX_TIMEOUT_MS, value));
    }

    private static String normalizeMethod(String method) {
        String value = StringUtils.hasText(method) ? method.trim().toUpperCase(Locale.ROOT) : "GET";
        return switch (value) {
            case "GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS" -> value;
            default -> throw new WorkflowHttpExecutionException(
                    "RUNTIME_HTTP_METHOD_UNSUPPORTED",
                    "Unsupported HTTP method: " + value);
        };
    }

    private static String firstText(Map<String, Object> secret, String... keys) {
        for (String key : keys) {
            Object value = secret.get(key);
            if (value != null && StringUtils.hasText(String.valueOf(value))) {
                return String.valueOf(value).trim();
            }
        }
        return null;
    }

    private static void rejectCrLf(String value, String field) {
        if (value != null && (value.contains("\r") || value.contains("\n"))) {
            throw new WorkflowHttpExecutionException(
                    "RUNTIME_HTTP_HEADER_INVALID",
                    "HTTP " + field + " must not contain CRLF");
        }
    }

    private static void rejectProtectedHeader(String name) {
        if (name != null && PROTECTED_HEADERS.contains(name.trim().toLowerCase(Locale.ROOT))) {
            throw new WorkflowHttpExecutionException(
                    "RUNTIME_HTTP_HEADER_PROTECTED",
                    "HTTP header is protected and cannot be set by node config: " + name.trim());
        }
    }

    private static String decode(String value) {
        try {
            return java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (Exception ex) {
            return value;
        }
    }

    private static String safeMessage(Exception ex) {
        String message = ex.getMessage();
        if (!StringUtils.hasText(message)) {
            return ex.getClass().getSimpleName();
        }
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("authorization") || lower.contains("cookie") || lower.contains("api-key")
                || lower.contains("apikey") || lower.contains("token=")) {
            return ex.getClass().getSimpleName();
        }
        return message.length() > 240 ? message.substring(0, 240) : message;
    }

    private static String safeUrlSummary(String url) {
        if (!StringUtils.hasText(url)) {
            return "";
        }
        try {
            URI uri = URI.create(url);
            String path = uri.getPath() == null ? "" : uri.getPath();
            return uri.getScheme() + "://" + uri.getHost()
                    + (uri.getPort() > 0 ? ":" + uri.getPort() : "")
                    + path;
        } catch (Exception ex) {
            return "[redacted-url]";
        }
    }

    public record HttpExecutionRequest(
            String method,
            String url,
            Map<String, String> queryParams,
            Map<String, String> headers,
            String bodyType,
            String body,
            Integer timeoutMs,
            String credentialRef,
            WorkflowExecutionIdentity identity
    ) {
        public HttpExecutionRequest(String method,
                                    String url,
                                    Map<String, String> queryParams,
                                    Map<String, String> headers,
                                    String bodyType,
                                    String body,
                                    Integer timeoutMs,
                                    String credentialRef,
                                    Long projectId,
                                    String projectCode) {
            this(method, url, queryParams, headers, bodyType, body, timeoutMs, credentialRef,
                    projectId != null || StringUtils.hasText(projectCode)
                            ? WorkflowExecutionIdentity.fromAgent(projectId, projectCode)
                            : WorkflowExecutionIdentity.untrustedDebug());
        }
    }

    public record HttpExecutionResult(
            boolean success,
            String code,
            int statusCode,
            Map<String, List<String>> headers,
            String body,
            Object parsedBody,
            String contentType,
            long durationMs,
            int bodyBytes,
            int redirectCount,
            String urlSummary,
            boolean retryableFailure
    ) {
        public HttpExecutionResult(boolean success,
                                   String code,
                                   int statusCode,
                                   Map<String, List<String>> headers,
                                   String body,
                                   Object parsedBody,
                                   String contentType,
                                   long durationMs,
                                   int bodyBytes,
                                   int redirectCount,
                                   String urlSummary) {
            this(success, code, statusCode, headers, body, parsedBody, contentType,
                    durationMs, bodyBytes, redirectCount, urlSummary, false);
        }

        public Map<String, Object> structuredOutput() {
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("statusCode", statusCode);
            output.put("headers", headers);
            output.put("body", body);
            if (parsedBody != null) {
                output.put("parsedBody", parsedBody);
            }
            output.put("contentType", contentType);
            output.put("durationMs", durationMs);
            output.put("bodyBytes", bodyBytes);
            output.put("redirectCount", redirectCount);
            output.put("url", urlSummary);
            return output;
        }

        public Map<String, Object> traceSummary() {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("statusCode", statusCode);
            summary.put("durationMs", durationMs);
            summary.put("bodyBytes", bodyBytes);
            summary.put("redirectCount", redirectCount);
            summary.put("url", urlSummary);
            summary.put("contentType", contentType);
            // Never persist response body into Trace summary.
            return summary;
        }
    }

    public static class WorkflowHttpExecutionException extends RuntimeException {
        private final String code;

        public WorkflowHttpExecutionException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    private record CredentialInjection(boolean credentialed, Set<String> sensitiveHeaderNames) {
        static CredentialInjection none() {
            return new CredentialInjection(false, new LinkedHashSet<>(Set.of(
                    "authorization", "proxy-authorization", "cookie", "set-cookie", "x-api-key", "api-key")));
        }
    }

    private record ResolvedTarget(URI uri, InetAddress address) {
    }

    /**
     * Connects to a previously validated IP while preserving SNI / hostname verification for the original host.
     */
    private static final class SniPinningSslSocketFactory extends SSLSocketFactory {
        private final SSLSocketFactory delegate;
        private final String hostname;
        private final InetAddress address;
        private final int port;

        private SniPinningSslSocketFactory(SSLSocketFactory delegate, String hostname, InetAddress address, int port) {
            this.delegate = delegate;
            this.hostname = hostname;
            this.address = address;
            this.port = port;
        }

        @Override
        public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
            Socket socket = delegate.createSocket(s, hostname, port, autoClose);
            applySni(socket);
            return socket;
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            Socket socket = delegate.createSocket();
            socket.connect(new InetSocketAddress(address, this.port > 0 ? this.port : port), 30_000);
            applySni(socket);
            return socket;
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
            Socket socket = delegate.createSocket();
            socket.bind(new InetSocketAddress(localHost, localPort));
            socket.connect(new InetSocketAddress(address, this.port > 0 ? this.port : port), 30_000);
            applySni(socket);
            return socket;
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            Socket socket = delegate.createSocket();
            socket.connect(new InetSocketAddress(address, this.port > 0 ? this.port : port), 30_000);
            applySni(socket);
            return socket;
        }

        @Override
        public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
            Socket socket = delegate.createSocket();
            socket.bind(new InetSocketAddress(localAddress, localPort));
            socket.connect(new InetSocketAddress(this.address, this.port > 0 ? this.port : port), 30_000);
            applySni(socket);
            return socket;
        }

        @Override
        public String[] getDefaultCipherSuites() {
            return delegate.getDefaultCipherSuites();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return delegate.getSupportedCipherSuites();
        }

        private void applySni(Socket socket) {
            if (socket instanceof SSLSocket sslSocket) {
                SSLParameters parameters = sslSocket.getSSLParameters();
                parameters.setServerNames(List.of(new SNIHostName(hostname)));
                sslSocket.setSSLParameters(parameters);
            }
        }
    }
}
