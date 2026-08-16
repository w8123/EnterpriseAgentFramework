package com.enterprise.ai.capability.internal;

import com.enterprise.ai.common.internalauth.InternalTransportSecurityPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Guards both configured platform hops and dynamic business Capability URLs.
 * Dynamic endpoints are checked immediately before network I/O.
 */
@Component
public class CapabilityOutboundTransportPolicy {

    private final InternalTransportSecurityPolicy policy;

    public CapabilityOutboundTransportPolicy(
            @Value("${reachai.internal.transport-mode:DEVELOPMENT_PLAINTEXT}") String mode,
            @Value("${services.runtime-service.url}") String runtimeUrl,
            @Value("${services.model-service.url}") String modelUrl,
            @Value("${services.knowledge-service.url}") String knowledgeUrl,
            Environment environment) {
        boolean production = InternalTransportSecurityPolicy.isProduction(environment.getActiveProfiles())
                || InternalTransportSecurityPolicy.isProduction(environment.getDefaultProfiles());
        this.policy = new InternalTransportSecurityPolicy(production, mode);
        policy.requireBaseUrl(runtimeUrl, "services.runtime-service.url");
        policy.requireBaseUrl(modelUrl, "services.model-service.url");
        policy.requireBaseUrl(knowledgeUrl, "services.knowledge-service.url");
    }

    public void requireAllowed(String url) {
        policy.requireRequestUrl(url, "Capability endpoint URL");
    }
}
