package com.enterprise.ai.control.a2a.infrastructure.transport.httpjson;

import com.enterprise.ai.control.a2a.application.identity.A2aCredentialBinding;
import com.enterprise.ai.control.a2a.application.port.A2aCredentialCipher;
import com.enterprise.ai.control.a2a.application.port.A2aRemoteAuthenticationPlanner;
import com.enterprise.ai.control.a2a.application.port.A2aRemoteProtocolTransport;
import com.enterprise.ai.control.a2a.domain.A2aDomainException;
import com.enterprise.ai.control.a2a.domain.identity.A2aCredential;
import com.enterprise.ai.control.a2a.domain.remoteagent.A2aRemoteInterface;
import com.enterprise.ai.control.a2a.infrastructure.A2aHubProperties;
import lombok.RequiredArgsConstructor;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.util.Timeout;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;

@Component
@RequiredArgsConstructor
public class SecureA2aHttpJsonTransport implements A2aRemoteProtocolTransport {

    private final A2aOutboundTargetPolicy targetPolicy;
    private final A2aCredentialCipher credentialCipher;
    private final A2aHubProperties properties;
    private final Clock clock;

    @Override
    public Response sendMessage(
            A2aRemoteInterface target,
            A2aRemoteAuthenticationPlanner.Binding authentication,
            A2aCredential credential,
            byte[] requestBody,
            int maxResponseBytes) {
        requireTarget(target);
        if (requestBody == null || requestBody.length == 0) {
            throw new A2aDomainException("A2A_OUTBOUND_MESSAGE_REQUIRED",
                    "outbound A2A request body is required");
        }
        return execute(target, authentication, credential, "POST",
                operationUri(target.url(), "message:send"), requestBody, maxResponseBytes);
    }

    @Override
    public Response getTask(
            A2aRemoteInterface target,
            A2aRemoteAuthenticationPlanner.Binding authentication,
            A2aCredential credential,
            String remoteTaskId,
            int historyLength,
            int maxResponseBytes) {
        requireTarget(target);
        requireTaskId(remoteTaskId);
        if (historyLength < 0 || historyLength > 100) {
            throw new A2aDomainException("A2A_HISTORY_LENGTH_INVALID",
                    "historyLength must be between 0 and 100");
        }
        return execute(target, authentication, credential, "GET",
                taskOperationUri(target, remoteTaskId, false, historyLength),
                null, maxResponseBytes);
    }

    @Override
    public Response cancelTask(
            A2aRemoteInterface target,
            A2aRemoteAuthenticationPlanner.Binding authentication,
            A2aCredential credential,
            String remoteTaskId,
            byte[] requestBody,
            int maxResponseBytes) {
        requireTarget(target);
        requireTaskId(remoteTaskId);
        if (requestBody == null || requestBody.length == 0) {
            throw new A2aDomainException("A2A_OUTBOUND_CANCEL_REQUEST_INVALID",
                    "outbound A2A cancel request body is required");
        }
        return execute(target, authentication, credential, "POST",
                taskOperationUri(target, remoteTaskId, true, null),
                requestBody, maxResponseBytes);
    }

    private Response execute(
            A2aRemoteInterface target,
            A2aRemoteAuthenticationPlanner.Binding authentication,
            A2aCredential credential,
            String method,
            URI endpoint,
            byte[] requestBody,
            int maxResponseBytes) {
        A2aOutboundTargetPolicy.ValidatedTarget verified = targetPolicy.validate(endpoint);
        DnsResolver pinnedDns = new PinnedDnsResolver(verified.host(), verified.addresses());
        var connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(pinnedDns)
                .build();
        RequestConfig config = RequestConfig.custom()
                .setConnectTimeout(timeout(properties.getOutbound().getConnectTimeout()))
                .setConnectionRequestTimeout(timeout(properties.getOutbound().getConnectTimeout()))
                .setResponseTimeout(timeout(properties.getOutbound().getResponseTimeout()))
                .build();
        HttpUriRequestBase request = "GET".equals(method)
                ? new HttpGet(verified.uri()) : new HttpPost(verified.uri());
        request.setConfig(config);
        request.setHeader("Accept", "application/a2a+json, application/json");
        request.setHeader("A2A-Version", target.protocolVersion());
        request.setHeader("Accept-Encoding", "identity");
        request.setHeader("User-Agent", "ReachAI-A2A-Hub/1.0");
        if (requestBody != null) {
            request.setHeader("Content-Type", "application/a2a+json");
            request.setEntity(new ByteArrayEntity(requestBody,
                    ContentType.create("application/a2a+json", StandardCharsets.UTF_8)));
        }
        applyAuthentication(request, authentication, credential);
        HttpClientContext context = HttpClientContext.create();
        Instant started = clock.instant();
        try (CloseableHttpClient client = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .disableRedirectHandling()
                .disableAutomaticRetries()
                .disableCookieManagement()
                .disableContentCompression()
                .build()) {
            return client.execute(request, context, response -> {
                int status = response.getCode();
                if (status >= 300 && status < 400) {
                    throw new A2aDomainException("A2A_REMOTE_REDIRECT_FORBIDDEN",
                            "remote A2A operation redirects are never followed");
                }
                if (status != 200) {
                    throw new A2aDomainException("A2A_REMOTE_OPERATION_HTTP_" + status,
                            "remote A2A operation returned HTTP " + status);
                }
                String contentType = header(response.getFirstHeader("Content-Type"));
                if (!jsonContentType(contentType)) {
                    throw new A2aDomainException("A2A_REMOTE_RESPONSE_CONTENT_TYPE_INVALID",
                            "remote A2A response must use a JSON content type");
                }
                byte[] body = boundedBody(response.getEntity(), maxResponseBytes);
                return new Response(body, contentType, status, tlsIdentity(context.getSSLSession()),
                        Math.max(0L, Duration.between(started, clock.instant()).toMillis()));
            });
        } catch (A2aDomainException known) {
            throw known;
        } catch (Exception failure) {
            A2aDomainException transport = new A2aDomainException(
                    "A2A_OUTBOUND_DELIVERY_UNCERTAIN",
                    "the remote A2A delivery outcome is uncertain; automatic retry is disabled");
            transport.initCause(failure);
            throw transport;
        }
    }

    private void applyAuthentication(
            HttpUriRequestBase request,
            A2aRemoteAuthenticationPlanner.Binding authentication,
            A2aCredential credential) {
        if (authentication == null || !authentication.authenticated()) {
            if (credential != null) {
                throw new A2aDomainException("A2A_OUTBOUND_CREDENTIAL_UNEXPECTED",
                        "an outbound credential cannot be used without an authentication binding");
            }
            return;
        }
        if (credential == null || !authentication.credentialId().equals(credential.id())) {
            throw new A2aDomainException("A2A_OUTBOUND_CREDENTIAL_BINDING_INVALID",
                    "the outbound credential does not match the reviewed authentication binding");
        }
        byte[] plaintext = credentialCipher.decrypt(
                new A2aCredentialCipher.EncryptedSecret(
                        credential.encryptionKeyId(), credential.encryptionNonce(),
                        credential.materialCiphertext()),
                A2aCredentialBinding.outbound(credential.credentialKey(), credential.versionNo()));
        try {
            String secret = new String(plaintext, StandardCharsets.UTF_8);
            if (secret.isBlank() || secret.length() > 8192
                    || secret.indexOf('\r') >= 0 || secret.indexOf('\n') >= 0) {
                throw new A2aDomainException("A2A_OUTBOUND_CREDENTIAL_VALUE_INVALID",
                        "the decrypted outbound credential cannot be used as an HTTP header value");
            }
            request.setHeader(authentication.headerName(),
                    (authentication.valuePrefix() == null ? "" : authentication.valuePrefix()) + secret);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    URI operationUri(String baseUrl, String operation) {
        try {
            URI base = URI.create(baseUrl);
            String path = base.getRawPath();
            String normalizedPath = (path.endsWith("/") ? path.substring(0, path.length() - 1) : path)
                    + "/" + operation;
            return rawUri(base, normalizedPath, null);
        } catch (IllegalArgumentException failure) {
            throw new A2aDomainException("A2A_REMOTE_INTERFACE_INVALID",
                    "the reviewed remote interface URL cannot form an operation endpoint");
        }
    }

    URI taskOperationUri(
            A2aRemoteInterface target,
            String remoteTaskId,
            boolean cancel,
            Integer historyLength) {
        requireTaskId(remoteTaskId);
        URI base;
        try {
            base = URI.create(target.url());
        } catch (IllegalArgumentException failure) {
            throw new A2aDomainException("A2A_REMOTE_INTERFACE_INVALID",
                    "the reviewed remote interface URL is invalid");
        }
        String basePath = base.getRawPath();
        String rawPath = (basePath.endsWith("/")
                ? basePath.substring(0, basePath.length() - 1) : basePath)
                + "/tasks/" + pathSegment(remoteTaskId) + (cancel ? ":cancel" : "");
        if (cancel) return rawUri(base, rawPath, null);
        StringBuilder query = new StringBuilder();
        if (target.tenant() != null && !target.tenant().isBlank()) {
            query.append("tenant=").append(queryValue(target.tenant().trim()));
        }
        if (historyLength != null) {
            if (query.length() > 0) query.append('&');
            query.append("historyLength=").append(historyLength);
        }
        return rawUri(base, rawPath, query.length() == 0 ? null : query.toString());
    }

    private void requireTarget(A2aRemoteInterface target) {
        if (target == null || !target.supportedByFirstRelease()) {
            throw new A2aDomainException("A2A_REMOTE_INTERFACE_NOT_SUPPORTED",
                    "outbound calls require a reviewed A2A 1.0 HTTP+JSON interface");
        }
    }

    private void requireTaskId(String value) {
        if (value == null || value.isBlank() || value.trim().length() > 128
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new A2aDomainException("A2A_REMOTE_TASK_ID_INVALID",
                    "remote Task id is invalid");
        }
    }

    private String pathSegment(String value) {
        return URLEncoder.encode(value.trim(), StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String queryValue(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private URI rawUri(URI base, String rawPath, String rawQuery) {
        String host = base.getHost();
        if (host == null || host.isBlank()) {
            throw new A2aDomainException("A2A_REMOTE_INTERFACE_INVALID",
                    "the reviewed remote interface host is invalid");
        }
        String authority = host.indexOf(':') >= 0 ? "[" + host + "]" : host;
        if (base.getPort() >= 0) authority += ":" + base.getPort();
        try {
            return URI.create(base.getScheme() + "://" + authority + rawPath
                    + (rawQuery == null ? "" : "?" + rawQuery));
        } catch (IllegalArgumentException failure) {
            throw new A2aDomainException("A2A_REMOTE_INTERFACE_INVALID",
                    "the reviewed remote interface cannot form a Task operation endpoint");
        }
    }

    private Timeout timeout(Duration duration) {
        Duration value = duration == null || duration.isNegative() || duration.isZero()
                ? Duration.ofSeconds(5) : duration;
        return Timeout.ofMilliseconds(value.toMillis());
    }

    private byte[] boundedBody(HttpEntity entity, int configuredMax) throws IOException {
        if (entity == null) {
            throw new A2aDomainException("A2A_REMOTE_RESPONSE_EMPTY",
                    "remote A2A response body is empty");
        }
        int max = Math.max(1024, Math.min(configuredMax, 32 * 1024 * 1024));
        if (entity.getContentLength() > max) {
            throw new A2aDomainException("A2A_REMOTE_RESPONSE_TOO_LARGE",
                    "remote A2A response exceeds the governed artifact size limit");
        }
        try (InputStream stream = entity.getContent()) {
            byte[] value = stream.readNBytes(max + 1);
            if (value.length == 0) {
                throw new A2aDomainException("A2A_REMOTE_RESPONSE_EMPTY",
                        "remote A2A response body is empty");
            }
            if (value.length > max) {
                throw new A2aDomainException("A2A_REMOTE_RESPONSE_TOO_LARGE",
                        "remote A2A response exceeds the governed artifact size limit");
            }
            return value;
        }
    }

    private String tlsIdentity(SSLSession session) {
        if (session == null) {
            throw new A2aDomainException("A2A_REMOTE_TLS_EVIDENCE_MISSING",
                    "verified TLS session evidence is missing");
        }
        try {
            Certificate[] certificates = session.getPeerCertificates();
            if (certificates.length == 0) throw new IllegalStateException("certificate missing");
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(certificates[0].getEncoded()));
        } catch (Exception failure) {
            throw new A2aDomainException("A2A_REMOTE_TLS_EVIDENCE_INVALID",
                    "remote TLS identity could not be fingerprinted");
        }
    }

    private boolean jsonContentType(String value) {
        if (value == null) return false;
        String normalized = value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return "application/a2a+json".equals(normalized)
                || "application/json".equals(normalized)
                || (normalized.startsWith("application/") && normalized.endsWith("+json"));
    }

    private String header(org.apache.hc.core5.http.Header value) {
        if (value == null || value.getValue() == null) return null;
        String normalized = value.getValue().replaceAll("[\\r\\n\\u0000-\\u001F]", "").trim();
        return normalized.length() > 256 ? normalized.substring(0, 256) : normalized;
    }

    private record PinnedDnsResolver(String allowedHost, InetAddress[] pinned) implements DnsResolver {
        private PinnedDnsResolver {
            pinned = pinned.clone();
        }

        @Override
        public InetAddress[] resolve(String host) throws UnknownHostException {
            if (!allowedHost.equalsIgnoreCase(host)) {
                throw new UnknownHostException("host is outside the pinned A2A target");
            }
            return pinned.clone();
        }

        @Override
        public String resolveCanonicalHostname(String host) throws UnknownHostException {
            if (!allowedHost.equalsIgnoreCase(host)) {
                throw new UnknownHostException("host is outside the pinned A2A target");
            }
            return allowedHost;
        }
    }
}
