package com.enterprise.ai.control.skillmarket;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class SkillMarketHttpTransport {

    private static final int MAX_REDIRECTS = 4;
    private static final String USER_AGENT = "ReachAI-Skill-Market/1.0";

    private final SkillMarketProperties properties;
    private final HttpClient httpClient;

    @Autowired
    public SkillMarketHttpTransport(SkillMarketProperties properties) {
        this(properties, HttpClient.newBuilder()
                .connectTimeout(nonZero(properties.getConnectTimeout(), Duration.ofSeconds(5)))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    SkillMarketHttpTransport(SkillMarketProperties properties, HttpClient httpClient) {
        this.properties = properties;
        this.httpClient = httpClient;
    }

    public HttpPayload get(URI uri,
                           Map<String, String> headers,
                           Set<String> allowedHosts,
                           long maxBytes) {
        if (maxBytes <= 0L) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
        URI current = uri;
        Map<String, String> currentHeaders = new LinkedHashMap<>(headers == null ? Map.of() : headers);
        for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
            validatePublicHttps(current, allowedHosts);
            HttpRequest.Builder request = HttpRequest.newBuilder(current)
                    .GET()
                    .timeout(nonZero(properties.getReadTimeout(), Duration.ofSeconds(30)))
                    .header("User-Agent", USER_AGENT);
            currentHeaders.forEach((name, value) -> {
                if (StringUtils.hasText(name) && StringUtils.hasText(value)) {
                    request.header(name, value);
                }
            });
            HttpResponse<InputStream> response;
            try {
                response = httpClient.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw SkillMarketErrors.upstream("Skill source request was interrupted", interrupted);
            } catch (IOException failure) {
                throw SkillMarketErrors.upstream("Skill source request failed", failure);
            }
            int status = response.statusCode();
            if (isRedirect(status)) {
                closeQuietly(response.body());
                if (redirects == MAX_REDIRECTS) {
                    throw SkillMarketErrors.upstream("Skill source redirected too many times");
                }
                String location = response.headers().firstValue("Location")
                        .orElseThrow(() -> SkillMarketErrors.upstream(
                                "Skill source returned a redirect without Location"));
                URI next = current.resolve(location);
                if (!sameHost(current, next)) {
                    currentHeaders.keySet().removeIf("authorization"::equalsIgnoreCase);
                }
                current = next;
                continue;
            }
            long declaredLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
            if (declaredLength > maxBytes) {
                closeQuietly(response.body());
                throw SkillMarketErrors.tooLarge(
                        "Remote Skill source exceeds the configured download limit");
            }
            byte[] body = readLimited(response.body(), maxBytes);
            return new HttpPayload(status, current, response.headers().map(), body);
        }
        throw SkillMarketErrors.upstream("Skill source redirect handling failed");
    }

    private byte[] readLimited(InputStream input, long maxBytes) {
        try (InputStream stream = input;
             ByteArrayOutputStream output = new ByteArrayOutputStream((int) Math.min(maxBytes, 64 * 1024))) {
            byte[] buffer = new byte[16 * 1024];
            long total = 0L;
            int read;
            while ((read = stream.read(buffer)) >= 0) {
                if (read == 0) continue;
                total += read;
                if (total > maxBytes) {
                    throw SkillMarketErrors.tooLarge(
                            "Remote Skill source exceeds the configured download limit");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } catch (com.enterprise.ai.control.agentskill.AgentSkillException expected) {
            throw expected;
        } catch (IOException failure) {
            throw SkillMarketErrors.upstream("Remote Skill source could not be read", failure);
        }
    }

    private void validatePublicHttps(URI uri, Set<String> allowedHosts) {
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme()) || !StringUtils.hasText(uri.getHost())) {
            throw SkillMarketErrors.unsupportedSource("Remote Skill sources must use an HTTPS URL");
        }
        if (uri.getUserInfo() != null || uri.getFragment() != null || (uri.getPort() != -1 && uri.getPort() != 443)) {
            throw SkillMarketErrors.unsupportedSource(
                    "Remote Skill source URL contains unsupported authority, port, or fragment data");
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        boolean allowed = allowedHosts != null && allowedHosts.stream()
                .filter(StringUtils::hasText)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .anyMatch(host::equals);
        if (!allowed) {
            throw SkillMarketErrors.unsupportedSource("Remote Skill source host is not allowlisted: " + host);
        }
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (addresses.length == 0) {
                throw SkillMarketErrors.upstream("Remote Skill source host has no DNS address");
            }
            for (InetAddress address : addresses) {
                boolean allowedSyntheticProxy = properties.isAllowSyntheticProxyDns()
                        && isSyntheticProxyAddress(address);
                if (isNonPublic(address) && !allowedSyntheticProxy) {
                    throw SkillMarketErrors.unsupportedSource(
                            "Remote Skill source resolved to a non-public address");
                }
            }
        } catch (UnknownHostException failure) {
            throw SkillMarketErrors.upstream("Remote Skill source host could not be resolved", failure);
        }
    }

    static boolean isNonPublic(InetAddress address) {
        if (address == null || address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address && bytes.length == 4) {
            int first = Byte.toUnsignedInt(bytes[0]);
            int second = Byte.toUnsignedInt(bytes[1]);
            int third = Byte.toUnsignedInt(bytes[2]);
            return first == 0 || first == 10 || first == 127 || first >= 224
                    || (first == 100 && second >= 64 && second <= 127)
                    || (first == 169 && second == 254)
                    || (first == 172 && second >= 16 && second <= 31)
                    || (first == 192 && (second == 0 || second == 168
                    || (second == 88 && third == 99)))
                    || (first == 198 && (second == 18 || second == 19
                    || (second == 51 && third == 100)))
                    || (first == 203 && second == 0 && third == 113);
        }
        if (address instanceof Inet6Address && bytes.length == 16) {
            int first = Byte.toUnsignedInt(bytes[0]);
            return (first & 0xfe) == 0xfc
                    || (Byte.toUnsignedInt(bytes[0]) == 0x20
                    && Byte.toUnsignedInt(bytes[1]) == 0x01
                    && Byte.toUnsignedInt(bytes[2]) == 0x0d
                    && Byte.toUnsignedInt(bytes[3]) == 0xb8);
        }
        return false;
    }

    /**
     * Explicit fake-IP pools used by the supported local transparent-proxy setup.
     * These addresses remain rejected unless allowSyntheticProxyDns is enabled.
     */
    static boolean isSyntheticProxyAddress(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address && bytes.length == 4) {
            return Byte.toUnsignedInt(bytes[0]) == 198
                    && (Byte.toUnsignedInt(bytes[1]) == 18 || Byte.toUnsignedInt(bytes[1]) == 19);
        }
        if (address instanceof Inet6Address && bytes.length == 16) {
            return Byte.toUnsignedInt(bytes[0]) == 0xfd
                    && Byte.toUnsignedInt(bytes[1]) == 0xfe
                    && Byte.toUnsignedInt(bytes[2]) == 0xdc
                    && Byte.toUnsignedInt(bytes[3]) == 0xba
                    && Byte.toUnsignedInt(bytes[4]) == 0x98
                    && Byte.toUnsignedInt(bytes[5]) == 0x76
                    && Byte.toUnsignedInt(bytes[6]) == 0
                    && Byte.toUnsignedInt(bytes[7]) == 0;
        }
        return false;
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static boolean sameHost(URI first, URI second) {
        return first != null && second != null && first.getHost() != null && second.getHost() != null
                && first.getHost().equalsIgnoreCase(second.getHost());
    }

    private static void closeQuietly(InputStream input) {
        if (input == null) return;
        try {
            input.close();
        } catch (IOException ignored) {
            // Nothing else can be recovered from a redirect/oversized response.
        }
    }

    private static Duration nonZero(Duration configured, Duration fallback) {
        return configured == null || configured.isZero() || configured.isNegative() ? fallback : configured;
    }

    public record HttpPayload(
            int status,
            URI finalUri,
            Map<String, java.util.List<String>> headers,
            byte[] body) {

        public HttpPayload {
            body = body == null ? new byte[0] : body.clone();
        }

        @Override
        public byte[] body() {
            return body.clone();
        }
    }
}
