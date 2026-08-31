package com.enterprise.ai.control.a2a.infrastructure.transport.httpjson;

import com.enterprise.ai.control.a2a.domain.A2aDomainException;
import com.enterprise.ai.control.a2a.infrastructure.A2aHubProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.net.IDN;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Locale;

@Component
@RequiredArgsConstructor
public class A2aOutboundTargetPolicy {

    private final A2aHubProperties properties;

    public ValidatedTarget validate(URI requested) {
        URI normalized = normalize(requested);
        String host = canonicalHost(normalized.getHost());
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException exception) {
            throw new A2aDomainException("A2A_REMOTE_DNS_FAILED",
                    "the remote Agent host could not be resolved");
        }
        if (addresses.length == 0) {
            throw new A2aDomainException("A2A_REMOTE_DNS_EMPTY",
                    "the remote Agent host has no resolved addresses");
        }
        boolean privateAllowed = properties.getOutbound().isPrivateNetworkAllowed();
        for (InetAddress address : addresses) {
            if (!isAllowed(address, privateAllowed)) {
                throw new A2aDomainException("A2A_REMOTE_NETWORK_FORBIDDEN",
                        "the remote Agent target resolves to a forbidden network range");
            }
        }
        return new ValidatedTarget(normalized, host, addresses);
    }

    public URI normalize(URI value) {
        if (value == null || !value.isAbsolute() || value.getHost() == null
                || !"https".equalsIgnoreCase(value.getScheme())
                || value.getUserInfo() != null || value.getQuery() != null
                || value.getFragment() != null) {
            throw new A2aDomainException("A2A_REMOTE_CARD_URL_INVALID",
                    "remote Agent Card URLs must be absolute HTTPS URLs without credentials, query, or fragment");
        }
        int effectivePort = value.getPort() == -1 ? 443 : value.getPort();
        if (effectivePort <= 0 || effectivePort > 65535
                || properties.getOutbound().getAllowedPorts() == null
                || !properties.getOutbound().getAllowedPorts().contains(effectivePort)) {
            throw new A2aDomainException("A2A_REMOTE_CARD_PORT_INVALID",
                    "remote Agent Card URL port is not allowed by outbound policy");
        }
        String path = value.getRawPath();
        if (path == null || path.isBlank() || path.length() > 1000) {
            throw new A2aDomainException("A2A_REMOTE_CARD_PATH_INVALID",
                    "remote Agent Card URL path is required and must be at most 1000 characters");
        }
        String lowerPath = path.toLowerCase(Locale.ROOT);
        if (path.indexOf('\\') >= 0 || lowerPath.contains("%2e")
                || lowerPath.contains("%2f") || lowerPath.contains("%5c")) {
            throw new A2aDomainException("A2A_REMOTE_CARD_PATH_INVALID",
                    "remote Agent URL path contains ambiguous encoded separators or dot segments");
        }
        try {
            URI lexical = value.normalize();
            if (!lexical.getRawPath().equals(path)) {
                throw new A2aDomainException("A2A_REMOTE_CARD_PATH_INVALID",
                        "remote Agent Card URL path cannot contain dot segments");
            }
            String asciiHost = canonicalHost(value.getHost());
            String authority = asciiHost.indexOf(':') >= 0 ? "[" + asciiHost + "]" : asciiHost;
            if (value.getPort() >= 0) authority += ":" + value.getPort();
            URI normalized = URI.create("https://" + authority + path);
            return normalized;
        } catch (IllegalArgumentException exception) {
            throw new A2aDomainException("A2A_REMOTE_CARD_URL_INVALID",
                    "remote Agent Card URL is invalid");
        }
    }

    private boolean isAllowed(InetAddress address, boolean privateAllowed) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        if (address instanceof Inet4Address ipv4) {
            return allowedIpv4(ipv4.getAddress(), privateAllowed);
        }
        if (address instanceof Inet6Address ipv6) {
            return allowedIpv6(ipv6.getAddress(), privateAllowed);
        }
        return false;
    }

    private String canonicalHost(String value) {
        String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT);
        return normalized.indexOf(':') >= 0 ? normalized : IDN.toASCII(normalized);
    }

    private boolean allowedIpv4(byte[] bytes, boolean privateAllowed) {
        int first = Byte.toUnsignedInt(bytes[0]);
        int second = Byte.toUnsignedInt(bytes[1]);
        int third = Byte.toUnsignedInt(bytes[2]);
        if (first == 10 || (first == 172 && second >= 16 && second <= 31)
                || (first == 192 && second == 168)) {
            return privateAllowed;
        }
        return first != 0
                && first != 127
                && first < 224
                && !(first == 100 && second >= 64 && second <= 127)
                && !(first == 169 && second == 254)
                && !(first == 192 && second == 0 && (third == 0 || third == 2))
                && !(first == 198 && (second == 18 || second == 19))
                && !(first == 198 && second == 51 && third == 100)
                && !(first == 203 && second == 0 && third == 113);
    }

    private boolean allowedIpv6(byte[] bytes, boolean privateAllowed) {
        int first = Byte.toUnsignedInt(bytes[0]);
        if ((first & 0xfe) == 0xfc) {
            return privateAllowed;
        }
        boolean globalUnicast = (first & 0xe0) == 0x20;
        boolean documentation = first == 0x20
                && Byte.toUnsignedInt(bytes[1]) == 0x01
                && Byte.toUnsignedInt(bytes[2]) == 0x0d
                && Byte.toUnsignedInt(bytes[3]) == 0xb8;
        return globalUnicast && !documentation;
    }

    public record ValidatedTarget(URI uri, String host, InetAddress[] addresses) {
        public ValidatedTarget {
            addresses = addresses == null ? new InetAddress[0] : addresses.clone();
        }

        @Override
        public InetAddress[] addresses() {
            return addresses.clone();
        }

        public String addressFamilySummary() {
            long ipv4 = Arrays.stream(addresses).filter(Inet4Address.class::isInstance).count();
            long ipv6 = addresses.length - ipv4;
            return "ipv4=" + ipv4 + ",ipv6=" + ipv6;
        }
    }
}
