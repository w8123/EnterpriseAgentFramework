package com.enterprise.ai.internalauth;

import com.enterprise.ai.common.internalauth.InternalTransportSecurityPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Production startup gate for Knowledge's embedding/model-service hop. */
@Component
public class KnowledgeInternalTransportProductionGuard {

    public KnowledgeInternalTransportProductionGuard(
            @Value("${reachai.internal.transport-mode:DEVELOPMENT_PLAINTEXT}") String mode,
            @Value("${embedding.model-service.url}") String modelUrl,
            Environment environment) {
        boolean production = InternalTransportSecurityPolicy.isProduction(environment.getActiveProfiles())
                || InternalTransportSecurityPolicy.isProduction(environment.getDefaultProfiles());
        InternalTransportSecurityPolicy policy = new InternalTransportSecurityPolicy(production, mode);
        policy.requireBaseUrl(modelUrl, "embedding.model-service.url");
    }
}
