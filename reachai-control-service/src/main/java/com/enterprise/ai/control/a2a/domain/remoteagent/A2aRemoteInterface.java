package com.enterprise.ai.control.a2a.domain.remoteagent;

import com.enterprise.ai.control.a2a.domain.A2aDomainException;
import com.enterprise.ai.control.a2a.domain.A2aDomainText;

import java.net.URI;

public record A2aRemoteInterface(
        String interfaceKey,
        String url,
        String protocolBinding,
        String protocolVersion,
        String tenant) {

    public A2aRemoteInterface {
        interfaceKey = A2aDomainText.requireText(interfaceKey, "interfaceKey");
        url = A2aDomainText.requireText(url, "interface.url");
        protocolBinding = A2aDomainText.requireText(protocolBinding, "interface.protocolBinding");
        protocolVersion = A2aDomainText.requireText(protocolVersion, "interface.protocolVersion");
        tenant = tenant == null || tenant.isBlank() ? null : tenant.trim();
        if (tenant != null && tenant.length() > 256) {
            throw new A2aDomainException("A2A_REMOTE_INTERFACE_TENANT_INVALID",
                    "remote interface tenant must not exceed 256 characters");
        }
        URI parsed = URI.create(url);
        if (!parsed.isAbsolute() || parsed.getHost() == null
                || !"https".equalsIgnoreCase(parsed.getScheme())
                || parsed.getRawUserInfo() != null || parsed.getRawQuery() != null
                || parsed.getRawFragment() != null) {
            throw new A2aDomainException("A2A_REMOTE_INTERFACE_INVALID",
                    "remote interfaces must use absolute HTTPS URLs without credentials, query, or fragment");
        }
    }

    public boolean supportedByFirstRelease() {
        return "HTTP+JSON".equalsIgnoreCase(protocolBinding) && "1.0".equals(protocolVersion);
    }
}
