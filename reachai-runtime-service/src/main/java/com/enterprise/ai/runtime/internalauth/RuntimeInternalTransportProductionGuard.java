package com.enterprise.ai.runtime.internalauth;

import com.enterprise.ai.common.internalauth.InternalTransportSecurityPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Production startup gate for Runtime's trusted internal service destinations. */
@Component
public class RuntimeInternalTransportProductionGuard {

    public RuntimeInternalTransportProductionGuard(
            @Value("${reachai.internal.transport-mode:DEVELOPMENT_PLAINTEXT}") String mode,
            @Value("${services.control-service.url}") String controlUrl,
            @Value("${services.capability-service.url}") String capabilityUrl,
            @Value("${services.model-service.url}") String modelUrl,
            @Value("${services.knowledge-service.url}") String knowledgeUrl,
            Environment environment) {
        boolean production = InternalTransportSecurityPolicy.isProduction(environment.getActiveProfiles())
                || InternalTransportSecurityPolicy.isProduction(environment.getDefaultProfiles());
        InternalTransportSecurityPolicy policy = new InternalTransportSecurityPolicy(production, mode);
        policy.requireBaseUrl(controlUrl, "services.control-service.url");
        policy.requireBaseUrl(capabilityUrl, "services.capability-service.url");
        policy.requireBaseUrl(modelUrl, "services.model-service.url");
        policy.requireBaseUrl(knowledgeUrl, "services.knowledge-service.url");
    }
}
