package com.enterprise.ai.runtime.execution.http;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Centralized HTTP egress policy for Workflow HTTP_REQUEST nodes.
 * Production defaults deny loopback, link-local, multicast, metadata and private ranges
 * unless explicitly allowlisted.
 *
 * <p>DNS is resolved exactly once per validation call; callers must reuse the returned addresses
 * for connection pinning and must not call {@code InetAddress.getAllByName} again.</p>
 */
@Component
public class WorkflowHttpEgressPolicy {

    public static final String ERROR_SCHEME = "RUNTIME_HTTP_EGRESS_SCHEME_DENIED";
    public static final String ERROR_HOST = "RUNTIME_HTTP_EGRESS_HOST_DENIED";
    public static final String ERROR_DNS = "RUNTIME_HTTP_EGRESS_DNS_DENIED";
    public static final String ERROR_REDIRECT = "RUNTIME_HTTP_EGRESS_REDIRECT_DENIED";

    private final boolean allowPrivateNetworks;
    private final Set<String> hostAllowlist;
    private final int maxRedirects;
    private final WorkflowDnsResolver dnsResolver;

    @Autowired
    public WorkflowHttpEgressPolicy(
            @Value("${reachai.runtime.http.egress.allow-private-networks:false}") boolean allowPrivateNetworks,
            @Value("${reachai.runtime.http.egress.host-allowlist:}") String hostAllowlist,
            @Value("${reachai.runtime.http.egress.max-redirects:3}") int maxRedirects,
            @Autowired(required = false) WorkflowDnsResolver dnsResolver) {
        this.allowPrivateNetworks = allowPrivateNetworks;
        this.hostAllowlist = parseAllowlist(hostAllowlist);
        this.maxRedirects = Math.max(0, Math.min(maxRedirects, 10));
        this.dnsResolver = dnsResolver == null ? new JdkWorkflowDnsResolver() : dnsResolver;
    }

    public static WorkflowHttpEgressPolicy permissiveForTests() {
        return new WorkflowHttpEgressPolicy(true, "localhost,127.0.0.1", 3, new JdkWorkflowDnsResolver());
    }

    public static WorkflowHttpEgressPolicy permissiveForTests(WorkflowDnsResolver dnsResolver) {
        return new WorkflowHttpEgressPolicy(true, "localhost,127.0.0.1", 3, dnsResolver);
    }

    public static WorkflowHttpEgressPolicy productionDefault() {
        return new WorkflowHttpEgressPolicy(false, "", 3, new JdkWorkflowDnsResolver());
    }

    public int maxRedirects() {
        return maxRedirects;
    }

    public WorkflowDnsResolver dnsResolver() {
        return dnsResolver;
    }

    public ResolvedHost validateUri(String rawUri) {
        return validateUri(rawUri, false);
    }

    public ResolvedHost validateRedirectTarget(String rawUri) {
        return validateUri(rawUri, true);
    }

    private ResolvedHost validateUri(String rawUri, boolean redirect) {
        if (!StringUtils.hasText(rawUri)) {
            throw new WorkflowHttpEgressException(
                    redirect ? ERROR_REDIRECT : ERROR_HOST,
                    "HTTP URL is required");
        }
        URI uri;
        try {
            uri = URI.create(rawUri.trim());
        } catch (Exception ex) {
            throw new WorkflowHttpEgressException(
                    redirect ? ERROR_REDIRECT : ERROR_HOST,
                    "HTTP URL is invalid");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new WorkflowHttpEgressException(ERROR_SCHEME, "Only http/https URLs are allowed");
        }
        String host = uri.getHost();
        if (!StringUtils.hasText(host)) {
            throw new WorkflowHttpEgressException(
                    redirect ? ERROR_REDIRECT : ERROR_HOST,
                    "HTTP URL host is required");
        }
        String normalizedHost = host.trim().toLowerCase(Locale.ROOT);
        if (isMetadataHost(normalizedHost)) {
            throw new WorkflowHttpEgressException(
                    redirect ? ERROR_REDIRECT : ERROR_HOST,
                    "HTTP target host is denied by egress policy");
        }
        InetAddress[] addresses;
        try {
            addresses = dnsResolver.resolve(normalizedHost);
        } catch (UnknownHostException ex) {
            throw new WorkflowHttpEgressException(ERROR_DNS, "HTTP target host DNS resolution failed");
        }
        if (addresses == null || addresses.length == 0) {
            throw new WorkflowHttpEgressException(ERROR_DNS, "HTTP target host DNS resolution returned empty");
        }
        if (!hostAllowlist.contains(normalizedHost)) {
            for (InetAddress address : addresses) {
                if (isDeniedAddress(address)) {
                    throw new WorkflowHttpEgressException(
                            redirect ? ERROR_REDIRECT : ERROR_DNS,
                            "HTTP target address is denied by egress policy");
                }
            }
        }
        return new ResolvedHost(uri, addresses);
    }

    private boolean isDeniedAddress(InetAddress address) {
        if (address == null) {
            return true;
        }
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isMulticastAddress()
                || address.isSiteLocalAddress() && !allowPrivateNetworks) {
            return true;
        }
        String hostAddress = address.getHostAddress();
        if (hostAddress == null) {
            return true;
        }
        String ip = hostAddress.toLowerCase(Locale.ROOT);
        if (ip.startsWith("169.254.") || ip.startsWith("fe80:") || "0.0.0.0".equals(ip) || "::".equals(ip)) {
            return true;
        }
        if (isMetadataIp(ip)) {
            return true;
        }
        if (!allowPrivateNetworks && (ip.startsWith("10.")
                || ip.startsWith("192.168.")
                || isIn172Private(ip)
                || ip.startsWith("fc")
                || ip.startsWith("fd"))) {
            return true;
        }
        return false;
    }

    private static boolean isIn172Private(String ip) {
        if (!ip.startsWith("172.")) {
            return false;
        }
        String[] parts = ip.split("\\.");
        if (parts.length < 2) {
            return false;
        }
        try {
            int second = Integer.parseInt(parts[1]);
            return second >= 16 && second <= 31;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    private static boolean isMetadataHost(String host) {
        return "metadata.google.internal".equals(host)
                || "metadata".equals(host)
                || "169.254.169.254".equals(host)
                || "metadata.azure.com".equals(host);
    }

    private static boolean isMetadataIp(String ip) {
        return "169.254.169.254".equals(ip) || "fd00:ec2::254".equals(ip);
    }

    private static Set<String> parseAllowlist(String raw) {
        Set<String> values = new LinkedHashSet<>();
        if (!StringUtils.hasText(raw)) {
            return values;
        }
        Arrays.stream(raw.split("[,\\s]+"))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .map(item -> item.toLowerCase(Locale.ROOT))
                .forEach(values::add);
        return values;
    }

    public record ResolvedHost(URI uri, InetAddress[] addresses) {
        public InetAddress primaryAddress() {
            return addresses[0];
        }
    }

    public static class WorkflowHttpEgressException extends RuntimeException {
        private final String code;

        public WorkflowHttpEgressException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
