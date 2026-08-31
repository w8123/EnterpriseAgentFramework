package com.enterprise.ai.control.a2a.infrastructure.transport.httpjson;

import com.enterprise.ai.control.a2a.application.port.A2aRemoteCardFetcher;
import com.enterprise.ai.control.a2a.domain.A2aDomainException;
import com.enterprise.ai.control.a2a.infrastructure.A2aHubProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.util.Timeout;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;

@Component
@RequiredArgsConstructor
public class SecureA2aRemoteCardFetcher implements A2aRemoteCardFetcher {

    private final A2aOutboundTargetPolicy targetPolicy;
    private final A2aHubProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Override
    public URI normalize(URI cardUri) {
        return targetPolicy.normalize(cardUri);
    }

    @Override
    public FetchResult fetch(URI cardUri) {
        A2aOutboundTargetPolicy.ValidatedTarget target = targetPolicy.validate(cardUri);
        DnsResolver pinnedDns = new PinnedDnsResolver(target.host(), target.addresses());
        var connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(pinnedDns)
                .build();
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(timeout(properties.getOutbound().getConnectTimeout()))
                .setResponseTimeout(timeout(properties.getOutbound().getResponseTimeout()))
                .setConnectionRequestTimeout(timeout(properties.getOutbound().getConnectTimeout()))
                .build();
        HttpGet request = new HttpGet(target.uri());
        request.setHeader("Accept", "application/a2a+json, application/json");
        request.setHeader("User-Agent", "ReachAI-A2A-Hub/1.0");
        request.setConfig(requestConfig);
        HttpClientContext context = HttpClientContext.create();
        Instant started = clock.instant();
        try (CloseableHttpClient client = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .disableRedirectHandling()
                .disableAutomaticRetries()
                .disableCookieManagement()
                .build()) {
            return client.execute(request, context, response -> {
                int status = response.getCode();
                if (status >= 300 && status < 400) {
                    throw new A2aDomainException("A2A_REMOTE_REDIRECT_FORBIDDEN",
                            "remote Agent Card redirects are not followed; review the final URL explicitly");
                }
                if (status != 200) {
                    throw new A2aDomainException("A2A_REMOTE_CARD_HTTP_FAILED",
                            "remote Agent Card returned HTTP " + status);
                }
                String contentType = header(response.getFirstHeader("Content-Type"));
                if (!jsonContentType(contentType)) {
                    throw new A2aDomainException("A2A_REMOTE_CARD_CONTENT_TYPE_INVALID",
                            "remote Agent Card must use a JSON content type");
                }
                byte[] body = boundedBody(response.getEntity(),
                        properties.getOutbound().getMaxAgentCardBytes());
                String tlsIdentity = tlsIdentity(context.getSSLSession());
                long latency = Duration.between(started, clock.instant()).toMillis();
                String evidence = evidence(target, latency, body.length, contentType, tlsIdentity != null);
                return new FetchResult(
                        target.uri(), body, contentType,
                        header(response.getFirstHeader("ETag")),
                        header(response.getFirstHeader("Last-Modified")),
                        tlsIdentity, evidence);
            });
        } catch (A2aDomainException known) {
            throw known;
        } catch (Exception failure) {
            throw new A2aDomainException("A2A_REMOTE_CARD_FETCH_FAILED",
                    "remote Agent Card could not be fetched over verified HTTPS");
        }
    }

    private Timeout timeout(Duration duration) {
        Duration value = duration == null || duration.isNegative() || duration.isZero()
                ? Duration.ofSeconds(5) : duration;
        return Timeout.ofMilliseconds(value.toMillis());
    }

    private byte[] boundedBody(HttpEntity entity, int configuredMax) throws IOException {
        if (entity == null) {
            throw new A2aDomainException("A2A_REMOTE_CARD_EMPTY", "remote Agent Card body is empty");
        }
        int max = Math.max(1024, Math.min(configuredMax, 4 * 1024 * 1024));
        long declared = entity.getContentLength();
        if (declared > max) {
            throw new A2aDomainException("A2A_REMOTE_CARD_TOO_LARGE",
                    "remote Agent Card exceeds the configured size limit");
        }
        try (InputStream stream = entity.getContent()) {
            byte[] body = stream.readNBytes(max + 1);
            if (body.length == 0) {
                throw new A2aDomainException("A2A_REMOTE_CARD_EMPTY", "remote Agent Card body is empty");
            }
            if (body.length > max) {
                throw new A2aDomainException("A2A_REMOTE_CARD_TOO_LARGE",
                        "remote Agent Card exceeds the configured size limit");
            }
            return body;
        }
    }

    private String tlsIdentity(SSLSession session) {
        if (session == null) {
            throw new A2aDomainException("A2A_REMOTE_TLS_EVIDENCE_MISSING",
                    "verified TLS session evidence is missing");
        }
        try {
            Certificate[] certificates = session.getPeerCertificates();
            if (certificates.length == 0) {
                throw new A2aDomainException("A2A_REMOTE_TLS_EVIDENCE_MISSING",
                        "remote TLS peer certificate is missing");
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(certificates[0].getEncoded()));
        } catch (A2aDomainException known) {
            throw known;
        } catch (Exception failure) {
            throw new A2aDomainException("A2A_REMOTE_TLS_EVIDENCE_INVALID",
                    "remote TLS identity could not be fingerprinted");
        }
    }

    private String evidence(
            A2aOutboundTargetPolicy.ValidatedTarget target,
            long latencyMs,
            int bytes,
            String contentType,
            boolean tlsVerified) {
        try {
            return objectMapper.writeValueAsString(new NetworkEvidence(
                    "reachai.a2a-hub.remote-network-evidence.v1",
                    "HTTPS", true, false, tlsVerified, target.addresses().length,
                    target.addressFamilySummary(), latencyMs, bytes, contentType));
        } catch (Exception failure) {
            throw new A2aDomainException("A2A_REMOTE_EVIDENCE_FAILED",
                    "remote network evidence could not be serialized");
        }
    }

    private boolean jsonContentType(String value) {
        if (value == null) return false;
        String normalized = value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return "application/json".equals(normalized)
                || "application/a2a+json".equals(normalized)
                || (normalized.startsWith("application/") && normalized.endsWith("+json"));
    }

    private String header(org.apache.hc.core5.http.Header value) {
        if (value == null || value.getValue() == null || value.getValue().isBlank()) return null;
        String normalized = value.getValue().replaceAll("[\\r\\n\\u0000-\\u001F]", "").trim();
        return normalized.length() > 512 ? normalized.substring(0, 512) : normalized;
    }

    private record NetworkEvidence(
            String schema,
            String transport,
            boolean dnsPinned,
            boolean redirectsFollowed,
            boolean tlsVerified,
            int resolvedAddressCount,
            String addressFamilies,
            long latencyMs,
            int responseBytes,
            String contentType) {
    }

    private record PinnedDnsResolver(String allowedHost, InetAddress[] pinned) implements DnsResolver {
        private PinnedDnsResolver {
            pinned = pinned.clone();
        }

        @Override
        public InetAddress[] resolve(String host) throws UnknownHostException {
            if (!allowedHost.equalsIgnoreCase(host)) {
                throw new UnknownHostException("host is outside the pinned A2A discovery target");
            }
            return pinned.clone();
        }

        @Override
        public String resolveCanonicalHostname(String host) throws UnknownHostException {
            if (!allowedHost.equalsIgnoreCase(host)) {
                throw new UnknownHostException("host is outside the pinned A2A discovery target");
            }
            return allowedHost;
        }
    }
}
