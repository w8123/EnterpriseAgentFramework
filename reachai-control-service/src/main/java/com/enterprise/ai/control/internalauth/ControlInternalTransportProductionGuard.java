package com.enterprise.ai.control.internalauth;

import com.enterprise.ai.common.internalauth.InternalTransportSecurityPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Fails Control startup when production service hops lack TLS or an explicit mTLS-mesh attestation. */
@Component
public class ControlInternalTransportProductionGuard {

    public ControlInternalTransportProductionGuard(
            @Value("${reachai.internal.transport-mode:DEVELOPMENT_PLAINTEXT}") String mode,
            @Value("${services.runtime-service.url}") String runtimeUrl,
            @Value("${services.capability-service.url}") String capabilityUrl,
            @Value("${services.model-service.url}") String modelUrl,
            @Value("${services.knowledge-service.url}") String knowledgeUrl,
            Environment environment) {
        boolean production = InternalTransportSecurityPolicy.isProduction(environment.getActiveProfiles())
                || InternalTransportSecurityPolicy.isProduction(environment.getDefaultProfiles());
        InternalTransportSecurityPolicy policy = new InternalTransportSecurityPolicy(production, mode);
        policy.requireBaseUrl(runtimeUrl, "services.runtime-service.url");
        policy.requireBaseUrl(capabilityUrl, "services.capability-service.url");
        policy.requireBaseUrl(modelUrl, "services.model-service.url");
        policy.requireBaseUrl(knowledgeUrl, "services.knowledge-service.url");
    }
}
